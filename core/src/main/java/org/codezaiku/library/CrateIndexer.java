package org.codezaiku.library;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Minimal WRITE path for the framework-knowledge Library: index a Rust crate's public API surface
 * (pub items + their {@code ///} doc comments) into the Lucene {@code library} collection, so the push
 * loop can retrieve the CURRENT signature when the model writes an older version's API (rust-monitor's
 * {@code sysinfo::CpuExt} drift). This is the acquisition gap the canon needed — DevDocs has no
 * sysinfo/ratatui set, so we index the crate source already on disk in {@code ~/.cargo/registry}.
 *
 * <p>Schema matches what {@link LibraryIndex} reads: {@code framework} (StringField filter), {@code
 * version}, {@code title}, {@code content} (BM25), {@code content_stored}. No dense vector — BM25 only.
 * Appends to the existing index (OpenMode CREATE_OR_APPEND).
 */
public final class CrateIndexer {

    private CrateIndexer() {
    }

    // pub item: name capture in group(2). Covers fn/struct/enum/trait/type/const/mod (and impl methods).
    private static final Pattern PUB_ITEM = Pattern.compile(
            "^pub(?:\\s*\\([^)]*\\))?\\s+(?:async\\s+|unsafe\\s+|const\\s+|default\\s+)*"
                    + "(fn|struct|enum|trait|type|const|mod)\\s+([A-Za-z_]\\w*)");

    /** Index one crate dir into the index collection. Returns the number of chunks written. */
    public static int index(Path collectionDir, String framework, String version,
                            String crateName, Path crateSrcDir) throws IOException {
        List<Chunk> chunks = extract(crateName, crateSrcDir);
        if (chunks.isEmpty()) return 0;
        Files.createDirectories(collectionDir);
        Directory dir = FSDirectory.open(collectionDir);
        IndexWriterConfig cfg = new IndexWriterConfig(new StandardAnalyzer());
        cfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        String sourceUrl = "https://docs.rs/" + crateName + "/" + version;
        try (IndexWriter w = new IndexWriter(dir, cfg)) {
            for (Chunk c : chunks) {
                Document d = new Document();
                d.add(new StringField("id", framework + ":" + version + ":" + c.id, Field.Store.YES));
                d.add(new TextField("content", c.content, Field.Store.NO));
                d.add(new StoredField("content_stored", c.content));
                d.add(new StringField("framework", framework.toLowerCase(), Field.Store.YES));
                d.add(new StringField("version", version, Field.Store.YES));
                d.add(new StoredField("title", c.title));
                d.add(new StoredField("source_url", sourceUrl));
                d.add(new StringField("trust_tier", "package-source", Field.Store.YES));
                w.addDocument(d);
            }
            w.commit();
        }
        return chunks.size();
    }

    private record Chunk(String id, String title, String content) {
    }

    /** Walk the crate's src/ and emit one chunk per pub item (doc comment + signature). */
    private static List<Chunk> extract(String crateName, Path crateSrcDir) {
        List<Chunk> out = new ArrayList<>();
        Path src = crateSrcDir.resolve("src");
        Path base = Files.isDirectory(src) ? src : crateSrcDir;
        try (Stream<Path> walk = Files.walk(base)) {
            List<Path> files = walk.filter(p -> p.toString().endsWith(".rs"))
                    .filter(Files::isRegularFile).limit(2000).toList();
            for (Path f : files) extractFile(crateName, crateSrcDir, f, out);
        } catch (IOException ignored) {
            // best-effort
        }
        return out;
    }

    private static void extractFile(String crateName, Path crateRoot, Path file, List<Chunk> out) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            return;
        }
        String rel = crateRoot.relativize(file).toString();
        StringBuilder doc = new StringBuilder();
        for (String line : lines) {
            String t = line.strip();
            if (t.startsWith("///") || t.startsWith("//!")) {
                doc.append(t.replaceFirst("^//[/!]\\s?", "")).append('\n');
            } else if (t.startsWith("#[") || t.startsWith("#![") || t.isEmpty()) {
                // attributes / blank may sit between doc and item — keep the doc buffer
            } else {
                Matcher m = PUB_ITEM.matcher(t);
                if (m.find()) {
                    String kind = m.group(1);
                    String name = m.group(2);
                    String sig = t.length() > 240 ? t.substring(0, 240) : t;
                    String body = doc.length() == 0 ? "" : doc.toString().strip();
                    // Skip undocumented trivial items unless they're a type the model would call.
                    if (body.isEmpty() && !(kind.equals("struct") || kind.equals("trait")
                            || kind.equals("enum") || kind.equals("fn"))) {
                        doc.setLength(0);
                        continue;
                    }
                    String content = crateName + "::" + name + "  (" + kind + ")\n"
                            + sig + (body.isEmpty() ? "" : "\n" + body);
                    out.add(new Chunk(rel + "#" + name, crateName + "::" + name, content));
                }
                doc.setLength(0);
            }
        }
    }
}
