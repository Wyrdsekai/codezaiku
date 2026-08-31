package org.codezaiku.chat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Sessions as an archive: export, import, and therefore backup — one zip of a project's chat
 * store. The store was DESIGNED for this (markdown is the state format, the transcript is JSON
 * lines, `.codezaiku/project-id` makes the directory name stable), so archiving is a walk, not a
 * serialization.
 *
 * <p>Entries are stored relative to the store directory itself, never under the project UUID:
 * importing on another machine restores into THAT machine's store for the project, whatever id
 * its `.codezaiku/project-id` carries. A manifest entry records where the archive came from.
 *
 * <p>java.util.zip and not a shelled-out tar: the same three platforms the chat runs on, no PATH
 * dependency, and the extraction path is guarded here — every entry must resolve inside the
 * destination (the PathScope symlink lesson: check every path that came from outside).
 */
public final class SessionArchive {

    static final String MANIFEST = ".codezaiku-sessions-manifest";

    private SessionArchive() { }

    /** Default archive name, in cwd: the project's directory name plus a timestamp. */
    public static Path defaultOut(Path projectRoot) {
        return Path.of("codezaiku-sessions-" + projectRoot.toAbsolutePath().normalize().getFileName()
                + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".zip");
    }

    /**
     * Zip everything under {@code storeDir} (state, transcripts, handoffs, journal) into
     * {@code out}. Returns the entry names written, manifest excluded.
     */
    public static List<String> export(Path storeDir, Path out, String originNote) throws IOException {
        if (!Files.isDirectory(storeDir)) {
            throw new IOException("nothing to export: " + storeDir + " does not exist");
        }
        List<String> names = new ArrayList<>();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out))) {
            zip.putNextEntry(new ZipEntry(MANIFEST));
            zip.write(("exported " + LocalDateTime.now() + "\nfrom " + originNote + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            try (Stream<Path> walk = Files.walk(storeDir)) {
                for (Path p : walk.filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(Path::toString)).toList()) {
                    String rel = storeDir.relativize(p).toString().replace('\\', '/');
                    zip.putNextEntry(new ZipEntry(rel));
                    Files.copy(p, zip);
                    zip.closeEntry();
                    names.add(rel);
                }
            }
        }
        return names;
    }

    /** What an import did, per entry: restored, or left alone because it already existed. */
    public record Restored(List<String> restored, List<String> skipped) { }

    /**
     * Extract {@code zip} into {@code storeDir}. An entry that already exists is SKIPPED unless
     * {@code overwrite} — an import must not silently clobber the sessions already on this
     * machine; the caller decides, and the report says which happened to every entry.
     */
    public static Restored importInto(Path zip, Path storeDir, boolean overwrite) throws IOException {
        Files.createDirectories(storeDir);
        Path base = storeDir.toAbsolutePath().normalize();
        List<String> restored = new ArrayList<>(), skipped = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (e.isDirectory() || e.getName().equals(MANIFEST)) continue;
                Path dest = base.resolve(e.getName()).normalize();
                if (!dest.startsWith(base)) {
                    throw new IOException("archive entry escapes the store: " + e.getName());
                }
                if (Files.exists(dest) && !overwrite) {
                    skipped.add(e.getName());
                    continue;
                }
                Files.createDirectories(dest.getParent());
                try (OutputStream os = Files.newOutputStream(dest)) {
                    transfer(in, os);
                }
                restored.add(e.getName());
            }
        }
        return new Restored(restored, skipped);
    }

    private static void transfer(InputStream in, OutputStream os) throws IOException {
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) os.write(buf, 0, n);
    }
}
