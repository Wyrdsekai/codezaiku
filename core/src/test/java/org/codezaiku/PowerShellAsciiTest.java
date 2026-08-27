package org.codezaiku;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A `.ps1` must be pure ASCII.
 *
 * Windows PowerShell 5.1 reads a BOM-less script in the system codepage, so a UTF-8 em-dash arrives
 * as mojibake — and the replacement bytes can include a quote, which terminates a string early and
 * breaks parsing on a line whose only sin was punctuation. Measured: the installer failed with "The
 * string is missing the terminator" pointing at a `Write-Host` several lines away from the dash.
 *
 * This repository's prose style uses em-dashes everywhere, so the trap is easy to walk back into.
 */
class PowerShellAsciiTest {

    @Test
    void powershellScriptsAreAsciiOnly() throws Exception {
        Path scripts = Path.of("../scripts");
        assertTrue(Files.isDirectory(scripts), "scripts/ not found from the module directory");

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> s = Files.walk(scripts)) {
            for (Path p : s.filter(f -> f.getFileName().toString().endsWith(".ps1")).toList()) {
                String body = Files.readString(p, StandardCharsets.UTF_8);
                for (int i = 0; i < body.length(); i++) {
                    if (body.charAt(i) > 127) {
                        offenders.add(p.getFileName() + " contains '" + body.charAt(i)
                                + "' (U+" + Integer.toHexString(body.charAt(i)) + ")");
                        break;
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "PowerShell 5.1 will mis-read these and fail to parse: " + offenders);
    }
}
