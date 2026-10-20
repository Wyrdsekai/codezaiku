package org.codezaiku;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The GPU reader against stand-in kernel trees: what the kernel says, never a tool's exit code. */
class GpuTest {

    @TempDir Path sys;
    Path realDrm = Gpu.SYS_DRM, realKfd = Gpu.SYS_KFD, realDev = Gpu.DEV;
    static final ModelServer.Runner NO_NVIDIA = cmd -> new ModelServer.Result(0, "nvidia-smi not found. This is AMD country.\n");
    static final ModelServer.Runner NVIDIA = cmd -> new ModelServer.Result(0, "NVIDIA RTX 6000 Ada Generation, 49140\nNVIDIA RTX A6000, 49140\n");

    @BeforeEach void standIn() throws Exception {
        Files.createDirectories(sys.resolve("drm")); Files.createDirectories(sys.resolve("kfd")); Files.createDirectories(sys.resolve("dev"));
        Gpu.SYS_DRM = sys.resolve("drm"); Gpu.SYS_KFD = sys.resolve("kfd"); Gpu.DEV = sys.resolve("dev");
    }
    @AfterEach void restore() { Gpu.SYS_DRM = realDrm; Gpu.SYS_KFD = realKfd; Gpu.DEV = realDev; }

    Path card(String name, String vendor, String device, long vramBytes) throws Exception {
        Path d = sys.resolve("drm").resolve(name).resolve("device");
        Files.createDirectories(d);
        Files.writeString(d.resolve("vendor"), vendor + "\n");
        Files.writeString(d.resolve("device"), device + "\n");
        if (vramBytes >= 0) Files.writeString(d.resolve("mem_info_vram_total"), vramBytes + "\n");
        return d;
    }
    void kfdNode(String n, String props) throws Exception { Path d = sys.resolve("kfd").resolve(n); Files.createDirectories(d); Files.writeString(d.resolve("properties"), props); }

    @Test
    void aStubNvidiaSmiThatPrintsASentenceAndExitsZeroIsNotACard() throws Exception {
        assertNull(Gpu.nvidia(NO_NVIDIA));
        assertEquals(Gpu.Kind.none, Gpu.detect(NO_NVIDIA).kind());
        assertNull(Gpu.nvidia(cmd -> new ModelServer.Result(0, "16380\n")), "a bare number has no name: not a measured card");
        assertNull(Gpu.nvidia(cmd -> { throw new java.io.IOException("nvidia-smi: not found"); }));
    }

    @Test
    void theLargestNvidiaCardByNameAndMemoryComesFirstEvenBesideAnAmdDevice() throws Exception {
        card("card0", "0x1002", "0x1586", 32L << 30);
        Gpu g = Gpu.detect(NVIDIA);
        assertEquals(Gpu.Kind.nvidia, g.kind());
        assertEquals("NVIDIA RTX 6000 Ada Generation", g.name());
        assertEquals(48, (int) g.gb());
        assertEquals("cuda", g.build());
        assertEquals("NVIDIA RTX 6000 Ada Generation, 48 GB", g.describe());
    }

    @Test
    void theInstinctCardAsRentedIsReadFromTheKernelsComputeTopologyAndServesThroughRocm() throws Exception {
        card("card0", "0x1002", "0x74b5", 205822885888L);
        kfdNode("0", "cpu_cores_count 32\nsimd_count 0\ngfx_target_version 0\n");   // the CPU node comes first and says nothing
        kfdNode("1", "simd_count 1216\ngfx_target_version 90402\n");
        Gpu g = Gpu.detect(NO_NVIDIA);
        assertEquals(Gpu.Kind.amd, g.kind());
        assertEquals("AMD GPU 74b5", g.name());
        assertEquals(192, (int) g.gb());
        assertEquals("gfx942", g.gfx());
        assertTrue(g.instinct());
        assertEquals("rocm", g.build());
        assertEquals("AMD GPU 74b5 (gfx942), 192 GB set aside for it", g.describe());
        assertEquals("/dev/kfd is not here (the amdgpu kernel driver's compute interface)", g.missingDevices());
        Files.writeString(sys.resolve("dev").resolve("kfd"), "");
        assertEquals("/dev/dri is not here (the kernel's render devices)", g.missingDevices());
        Files.createDirectories(sys.resolve("dev").resolve("dri"));
        assertNull(g.missingDevices());
    }

    @Test
    void theComputeTopologysDecimalDecodesToTheGfxName() throws Exception {
        card("card0", "0x1002", "0x0000", 1L << 30);
        for (String[] c : List.of(new String[]{"110501", "gfx1151"}, new String[]{"90402", "gfx942"}, new String[]{"100100", "gfx1010"}, new String[]{"110000", "gfx1100"}, new String[]{"120001", "gfx1201"})) {
            kfdNode("1", "gfx_target_version " + c[0] + "\n");
            assertEquals(c[1], Gpu.detect(NO_NVIDIA).gfx(), c[0]);
        }
    }

    @Test
    void anApuWithoutTheComputeTopologyIsNamedFromItsPciIdAndServesThroughVulkan() throws Exception {
        card("card0", "0x1002", "0x1586", 32L << 30);   // Ryzen AI MAX: the BIOS carve-out is its memory
        Gpu g = Gpu.detect(NO_NVIDIA);
        assertEquals(Gpu.Kind.amd, g.kind());
        assertEquals(32, (int) g.gb());
        assertEquals("gfx1151", g.gfx());
        assertFalse(g.instinct());
        assertEquals("vulkan", g.build());
        Files.createDirectories(sys.resolve("dev").resolve("dri"));
        assertNull(g.missingDevices(), "Vulkan wants the render devices only, not /dev/kfd");
    }

    @Test
    void theAmdDeviceWithTheMostMemorySetAsideWinsAndOneWithoutAnyIsNotCounted() throws Exception {
        card("card0", "0x1002", "0x164e", 512L << 20);     // the APU with the default carve-out
        card("card1", "0x1002", "0x744c", 24L << 30);      // the RX 7900 beside it
        card("card2", "0x1002", "0x1234", -1);             // a device that says nothing about memory
        Gpu g = Gpu.detect(NO_NVIDIA);
        assertEquals("AMD GPU 744c", g.name());
        assertEquals(24, (int) g.gb());
        assertEquals("gfx1100", g.gfx());
    }

    @Test
    void anIntelRenderDeviceSharesTheSystemsMemory() throws Exception {
        card("card1", "0x8086", "0x7d55", -1);
        Gpu g = Gpu.detect(NO_NVIDIA);
        assertEquals(Gpu.Kind.intel, g.kind());
        assertEquals(0, (int) g.gb());
        assertEquals("vulkan", g.build());
        assertEquals("Intel GPU, sharing the system's memory", g.describe());
    }

    @Test
    void anAmdDeviceWithMemoryOutranksAnIntelOneAndAnUnknownVendorIsNothing() throws Exception {
        card("card0", "0x8086", "0x7d55", -1);
        card("card1", "0x1002", "0x1586", 32L << 30);
        assertEquals(Gpu.Kind.amd, Gpu.detect(NO_NVIDIA).kind());
        Files.walk(sys.resolve("drm")).sorted(java.util.Comparator.reverseOrder()).filter(p -> !p.equals(sys.resolve("drm"))).forEach(p -> p.toFile().delete());
        card("card0", "0x10de", "0x26b1", -1);   // an NVIDIA card the kernel lists but nvidia-smi cannot measure (no driver): not a GPU to serve on
        assertEquals(Gpu.Kind.none, Gpu.detect(NO_NVIDIA).kind());
        assertEquals("no GPU", Gpu.NONE.describe());
    }
}
