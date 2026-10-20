package org.codezaiku;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The suite never reads the GPU of the box it runs on: the test task points the kernel-tree readers at an empty folder under the
 * build directory (-Dcodezaiku.sys, -Dcodezaiku.dev). Without this a test that fakes "no card" sees the build box's own Intel iGPU and
 * walks on to serve on it (2026-10-20).
 */
class SuiteGpuTest {

    @Test
    void theGpuReadersLookAtAnEmptyTreeUnderTheBuildFolderNotThisBox() {
        String sys = System.getProperty("codezaiku.sys", ""), dev = System.getProperty("codezaiku.dev", "");
        assertTrue(sys.contains("/build/") && dev.contains("/build/"), "the test task names an empty tree: sys=" + sys + " dev=" + dev);
        assertTrue(Gpu.SYS_DRM.startsWith(sys) && Gpu.SYS_KFD.startsWith(sys) && Gpu.DEV.toString().equals(dev), Gpu.SYS_DRM + " " + Gpu.DEV);
        assertFalse(Files.exists(Gpu.SYS_DRM) && !Files.isDirectory(Gpu.SYS_DRM));
        assertEquals(Gpu.Kind.none, Gpu.detect(cmd -> new ModelServer.Result(1, "")).kind(), "nothing under the empty tree");
    }
}
