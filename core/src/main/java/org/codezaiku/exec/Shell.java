package org.codezaiku.exec;

import org.codezaiku.Config;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which shell CodeZaiku runs commands through — decided once, explicitly, and reportable.
 *
 * <p>On Windows this is not the trivial question it looks like. {@code ProcessBuilder("bash", ...)}
 * does not honour PATH order the way a shell does: Windows' {@code CreateProcess} searches
 * {@code C:\Windows\System32} BEFORE any PATH directory, and {@code System32\bash.exe} is the **WSL
 * launcher**. So on any machine with WSL installed, a bare {@code "bash"} dispatches every command
 * into the Linux distribution — measured with Git Bash sitting earlier on PATH and losing anyway:
 *
 * <pre>
 *   PATH order  >>> ...;C:\Program Files\Git\bin;...;C:\WINDOWS\system32
 *   uname       >>> Linux 6.18.33.2-microsoft-standard-WSL2
 * </pre>
 *
 * <p>That made the execution environment depend on whether WSL happened to be installed, with no
 * diagnostic anywhere: the same release ran against MSYS on one Windows box and against a Linux
 * distro on another, using a different toolchain and a different view of the filesystem
 * ({@code /mnt/c} is case-insensitive and has different exec-bit semantics). It also caused a real
 * defect — the test oracle passed a {@code C:\...} path into a shell that turned out to be Linux.
 *
 * <p>The default is therefore Git Bash, by ABSOLUTE path, matching the Git-for-Windows requirement
 * the docs already state. WSL remains available deliberately rather than accidentally: run CodeZaiku
 * inside the distribution, or set {@code CODEZAIKU_SHELL} to a shell of your choosing.
 */
public final class Shell {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private static final String RESOLVED = resolve();
    private static final String SOURCE = source(RESOLVED);

    /** The shell executable every command is dispatched through. */
    public static String command() {
        return RESOLVED;
    }

    /** A {@code -lc} process builder for {@code script}, using the resolved shell. */
    public static ProcessBuilder pb(String script) {
        return new ProcessBuilder(RESOLVED, "-lc", script);
    }

    /** Human-readable, for {@code doctor} and any result document that should not hide this. */
    public static String describe() {
        return RESOLVED + " (" + SOURCE + ")";
    }

    /** True when the resolved shell is the WSL launcher rather than a native shell. */
    public static boolean isWsl() {
        return WINDOWS && RESOLVED.toLowerCase(Locale.ROOT).contains("system32");
    }

    private static String resolve() {
        String forced = Config.get("CODEZAIKU_SHELL");
        if (forced != null && !forced.isBlank()) return forced.trim();
        if (!WINDOWS) return "bash";

        for (Path p : windowsCandidates()) {
            try {
                if (Files.isExecutable(p)) return p.toString();
            } catch (Exception ignored) {
                // an unparseable candidate is not a shell
            }
        }
        return "bash";   // nothing better found; CreateProcess will decide, and doctor will say so
    }

    /**
     * Git Bash first, in its usual install locations, then any bash on PATH that is NOT the WSL
     * launcher. System32 and WindowsApps are excluded deliberately: the first is WSL, the second
     * holds Store execution-alias stubs.
     */
    private static List<Path> windowsCandidates() {
        List<Path> out = new ArrayList<>();
        for (String base : new String[]{
                System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"),
                System.getenv("ProgramW6432"),
                System.getenv("LOCALAPPDATA") == null ? null : System.getenv("LOCALAPPDATA") + "\\Programs"}) {
            if (base == null || base.isBlank()) continue;
            out.add(Path.of(base, "Git", "bin", "bash.exe"));
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (dir.isBlank()) continue;
                String d = dir.toLowerCase(Locale.ROOT);
                if (d.contains("system32") || d.contains("windowsapps")) continue;
                try {
                    out.add(Path.of(dir, "bash.exe"));
                } catch (Exception ignored) {
                    // an unparseable PATH entry contributes no candidate
                }
            }
        }
        return out;
    }

    private static String source(String resolved) {
        String forced = Config.get("CODEZAIKU_SHELL");
        if (forced != null && !forced.isBlank()) return "CODEZAIKU_SHELL";
        if (!WINDOWS) return "default";
        if ("bash".equals(resolved)) return "no native bash found — CreateProcess will resolve it, "
                + "which on a machine with WSL installed means the WSL launcher";
        return resolved.toLowerCase(Locale.ROOT).contains("system32") ? "WSL launcher" : "native";
    }

    private Shell() { }
}
