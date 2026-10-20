package org.codezaiku;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The GPU this Linux machine has, read the way the kernel tells it, never from a tool's exit code.
 *
 * <p>An NVIDIA card is one {@code nvidia-smi} can measure: a name and a memory size. A rented AMD box ships a stub
 * {@code nvidia-smi} that prints "nvidia-smi not found. This is AMD country." and exits 0 (2026-10-20); its exit code made
 * it an NVIDIA machine. An AMD GPU is a {@code /sys/class/drm/card*} device of vendor {@code 0x1002} with memory set aside
 * for it ({@code mem_info_vram_total}: a discrete card's own memory, or the carve-out the BIOS gives an APU); its compute
 * target ({@code gfx942}, {@code gfx1151}) comes from the kernel's compute topology, else from the PCI id of the chips
 * households buy. An Intel GPU is vendor {@code 0x8086}; it has no memory of its own, it shares the system's.
 *
 * <p>Which llama.cpp build serves on it: CUDA on NVIDIA; ROCm on an Instinct card ({@code gfx9xx}, a compute card with no
 * graphics driver, so Vulkan is not a path there); Vulkan on a Radeon or an APU and on Intel. From REF_AMD_ROCM_OCT2026.
 */
public record Gpu(Kind kind, String name, double gb, String gfx) {

    public enum Kind { nvidia, amd, intel, none }

    /**
     * Where the readers look: the kernel's own trees, or the ones named by {@code -Dcodezaiku.sys=<dir>} and {@code -Dcodezaiku.dev=<dir>},
     * which the test task points at an empty folder so that no test reads the box it runs on (the build box has an Intel iGPU beside
     * its NVIDIA card, and a test faking "no card" went on to serve on it, 2026-10-20). A test points them at a stand-in tree.
     */
    private static final Path SYS = Path.of(System.getProperty("codezaiku.sys", "/sys")), DEVS = Path.of(System.getProperty("codezaiku.dev", "/dev"));
    public static volatile Path SYS_DRM = SYS.resolve("class/drm");
    public static volatile Path SYS_KFD = SYS.resolve("class/kfd/kfd/topology/nodes");
    public static volatile Path DEV = DEVS;

    public static final Gpu NONE = new Gpu(Kind.none, "", 0, "");

    /** The GPU, NVIDIA first (measured, not merely present), then the kernel's AMD and Intel devices. */
    public static Gpu detect(ModelServer.Runner runner) {
        Gpu n = nvidia(runner);
        return n != null ? n : kernel();
    }

    /** The AMD or Intel GPU the kernel lists, read from sysfs alone (no command runs); {@link #NONE} when it lists neither. */
    public static Gpu kernel() {
        Path amd = amdCardDir();
        if (amd != null) {
            long bytes = number(amd.resolve("mem_info_vram_total"));
            String id = text(amd.resolve("device")).replaceFirst("^0x", "");
            return new Gpu(Kind.amd, "AMD GPU " + id, Math.round(bytes / 1073741824.0), gfxTarget(id));
        }
        try (Stream<Path> cards = Files.list(SYS_DRM)) {
            for (Path c : cards.filter(p -> p.getFileName().toString().startsWith("card")).toList()) {
                if ("0x8086".equals(text(c.resolve("device").resolve("vendor")))) return new Gpu(Kind.intel, "Intel GPU", 0, "");
            }
        } catch (IOException | RuntimeException ignored) { }
        return NONE;
    }

    /** The largest NVIDIA card, by name and memory, or null: a line that is not "<name>, <number>" is not a card. */
    static Gpu nvidia(ModelServer.Runner runner) {
        try {
            ModelServer.Result r = runner.run(List.of("nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader,nounits"));
            if (r.code() != 0) return null;
            Gpu best = null;
            for (String line : r.out().split("\\R")) {
                int comma = line.lastIndexOf(',');
                if (comma < 0) continue;
                String mem = line.substring(comma + 1).strip();
                if (!mem.matches("\\d+")) continue;
                double gb = Math.round(Long.parseLong(mem) / 1024.0);
                if (best == null || gb > best.gb()) best = new Gpu(Kind.nvidia, line.substring(0, comma).strip(), gb, "");
            }
            return best;
        } catch (Exception e) { return null; }
    }

    /** The AMD device with the most memory set aside for it, or null. */
    static Path amdCardDir() {
        Path best = null; long bestBytes = 0;
        try (Stream<Path> cards = Files.list(SYS_DRM)) {
            for (Path c : cards.filter(p -> p.getFileName().toString().startsWith("card")).toList()) {
                Path d = c.resolve("device");
                if (!"0x1002".equals(text(d.resolve("vendor")))) continue;
                long bytes = number(d.resolve("mem_info_vram_total"));
                if (bytes > bestBytes) { bestBytes = bytes; best = d; }
            }
        } catch (IOException | RuntimeException ignored) { }
        return best;
    }

    /**
     * The AMD GPU's compute target, the name AMD's own builds go by: from the kernel's compute topology
     * ({@code gfx_target_version 90402} = 9.4.2 = gfx942, {@code 110501} = gfx1151), else from the PCI id of the chips
     * households buy; "" when nothing says.
     */
    static String gfxTarget(String pciId) {
        try (Stream<Path> nodes = Files.list(SYS_KFD)) {
            for (Path n : nodes.toList()) {
                Path props = n.resolve("properties");
                if (!Files.isRegularFile(props)) continue;
                for (String line : Files.readAllLines(props)) {
                    if (!line.startsWith("gfx_target_version")) continue;
                    String v = line.substring("gfx_target_version".length()).strip();
                    if (!v.matches("\\d{5,6}")) continue;
                    int major = Integer.parseInt(v.substring(0, v.length() - 4)), minor = Integer.parseInt(v.substring(v.length() - 4, v.length() - 2)), step = Integer.parseInt(v.substring(v.length() - 2));
                    return "gfx" + major + Integer.toHexString(minor) + Integer.toHexString(step);
                }
            }
        } catch (IOException | RuntimeException ignored) { }
        return switch (pciId.toLowerCase(Locale.ROOT)) {
            case "1586", "1587" -> "gfx1151";          // Ryzen AI MAX (Strix Halo)
            case "150e", "1114" -> "gfx1150";          // Ryzen AI 300 (Strix Point)
            case "15bf", "15c8" -> "gfx1103";          // Ryzen 7040 / 8040
            case "164e", "1681" -> "gfx1035";          // Ryzen 6000 / 7035
            case "744c", "7448", "747e" -> "gfx1100";  // Radeon RX 7900
            case "74b5" -> "gfx942";                   // Instinct MI300X (virtual function, as rented)
            default -> "";
        };
    }

    /** An Instinct compute card (gfx9xx: MI210, MI300X, MI350), which llama.cpp serves through ROCm, not Vulkan. */
    public boolean instinct() { return kind == Kind.amd && gfx.matches("gfx9\\d+[0-9a-f]*"); }

    /** The llama.cpp build that serves on this GPU, in a word: cuda, rocm, vulkan, or none. */
    public String build() {
        return switch (kind) {
            case nvidia -> "cuda";
            case amd -> instinct() ? "rocm" : "vulkan";
            case intel -> "vulkan";
            case none -> "none";
        };
    }

    /** The device files the container needs, and whether they are here: /dev/kfd for ROCm, /dev/dri for both. */
    public String missingDevices() {
        if (kind == Kind.nvidia || kind == Kind.none) return null;
        if (instinct() && !Files.exists(DEV.resolve("kfd"))) return "/dev/kfd is not here (the amdgpu kernel driver's compute interface)";
        if (!Files.isDirectory(DEV.resolve("dri"))) return "/dev/dri is not here (the kernel's render devices)";
        return null;
    }

    /** One line for a person: "AMD GPU 74b5 (gfx942), 196 GB set aside for it". */
    public String describe() {
        return switch (kind) {
            case nvidia -> name + ", " + (int) gb + " GB";
            case amd -> name + (gfx.isEmpty() ? "" : " (" + gfx + ")") + ", " + (int) gb + " GB set aside for it";
            case intel -> "Intel GPU, sharing the system's memory";
            case none -> "no GPU";
        };
    }

    private static String text(Path p) { try { return Files.readString(p).strip(); } catch (IOException | RuntimeException e) { return ""; } }
    private static long number(Path p) { String t = text(p); return t.matches("\\d+") ? Long.parseLong(t) : 0; }
}
