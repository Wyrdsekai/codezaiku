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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Indexes the EVERGREEN knowledge-packs (conceptual patterns, language gotchas, ecosystem
 * anti-patterns) into the Lucene library so the push surfaces them. Only version-INDEPENDENT idiom
 * prose is ported here — version-pinned templates/conventions rot and are excluded (they get
 * regenerated from the substrate instead). Each chunk carries its {@code applies_to}/{@code keys}/
 * domain tags as REPEATED {@code framework} fields, so a project that scopes to any of those tags
 * retrieves it. {@code trust_tier = evergreen-idiom}.
 */
public final class KnowledgePackIndexer {

    private KnowledgePackIndexer() {
    }

    private static final Pattern LIST_TAGS =
            Pattern.compile("(?m)^(?:applies_to|keys|tags)\\s*:\\s*\\[?([^\\]\\n]+)\\]?");
    private static final Pattern DOMAIN =
            Pattern.compile("(?m)^domain\\s*:\\s*([A-Za-z0-9_-]+)");
    private static final Pattern H2 = Pattern.compile("(?m)^#{2,3}\\s+(.+)$"); // split on ## AND ### subsections

    /** Index every .md under {@code root} (recursively) that the {@code include} predicate accepts. */
    public static int indexTree(Path collectionDir, Path root,
                                Predicate<Path> include) throws IOException {
        if (!Files.isDirectory(root)) return 0;
        Files.createDirectories(collectionDir);
        Directory dir = FSDirectory.open(collectionDir);
        IndexWriterConfig cfg = new IndexWriterConfig(new StandardAnalyzer());
        cfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        int total = 0;
        try (IndexWriter w = new IndexWriter(dir, cfg);
             Stream<Path> walk = Files.walk(root)) {
            // Idempotent re-index: drop the previous evergreen docs before re-adding (no duplicates).
            w.deleteDocuments(new org.apache.lucene.index.Term("trust_tier", "evergreen-idiom"));
            List<Path> files = walk.filter(p -> p.toString().endsWith(".md"))
                    .filter(Files::isRegularFile).filter(include).toList();
            for (Path f : files) total += indexFile(w, root, f);
            w.commit();
        }
        return total;
    }

    private static int indexFile(IndexWriter w, Path root, Path file) throws IOException {
        String body;
        try {
            body = Files.readString(file);
        } catch (IOException e) {
            return 0;
        }
        if (body.isBlank()) return 0;
        Set<String> tags = tagsFor(root, file, body);
        if (tags.isEmpty()) return 0; // untaggable → can't be scoped → skip
        String rel = root.relativize(file).toString();
        List<Section> sections = sections(body, rel);
        int n = 0;
        for (Section s : sections) {
            Document d = new Document();
            d.add(new StringField("id", "kp:" + rel + "#" + n, Field.Store.YES));
            d.add(new TextField("content", s.content, Field.Store.NO));
            d.add(new StoredField("content_stored", s.content));
            for (String tag : tags) {
                d.add(new StringField("framework", tag, Field.Store.YES)); // repeated → matches any tag
            }
            d.add(new StringField("version", "evergreen", Field.Store.YES)); // must match existing index options
            d.add(new StoredField("title", s.title));
            d.add(new StoredField("source_url", "knowledge-packs/" + rel));
            d.add(new StringField("trust_tier", "evergreen-idiom", Field.Store.YES));
            w.addDocument(d);
            n++;
        }
        return n;
    }

    private record Section(String title, String content) {
    }

    /** Split on H2 headings; whole-file fallback. Each section gets the file title prefix for context. */
    private static List<Section> sections(String body, String rel) {
        String fileTitle = rel.substring(rel.lastIndexOf('/') + 1).replaceFirst("\\.md$", "");
        List<Section> out = new ArrayList<>();
        Matcher m = H2.matcher(body);
        List<int[]> spans = new ArrayList<>();
        List<String> heads = new ArrayList<>();
        while (m.find()) {
            spans.add(new int[]{m.start(), m.end()});
            heads.add(m.group(1).strip());
        }
        if (spans.isEmpty()) {
            out.add(new Section(fileTitle, trim(body, 1400)));
            return out;
        }
        for (int i = 0; i < spans.size(); i++) {
            int start = spans.get(i)[0];
            int end = (i + 1 < spans.size()) ? spans.get(i + 1)[0] : body.length();
            String sec = body.substring(start, end).strip();
            out.add(new Section(fileTitle + " — " + heads.get(i), trim(sec, 1400)));
        }
        return out;
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Tags = file frontmatter applies_to/keys/tags + domain, else the parent dir's _tags.yaml. */
    private static Set<String> tagsFor(Path root, Path file, String body) {
        Set<String> tags = new LinkedHashSet<>();
        collectTags(body, tags);
        Path sidecar = file.getParent().resolve("_tags.yaml");
        if (tags.isEmpty() && Files.isRegularFile(sidecar)) {
            try {
                collectTags(Files.readString(sidecar), tags);
            } catch (IOException ignored) {
                // best-effort
            }
        }
        return tags;
    }

    private static void collectTags(String text, Set<String> tags) {
        Matcher m = LIST_TAGS.matcher(text);
        while (m.find()) {
            for (String part : m.group(1).split(",")) {
                String t = part.trim().replaceAll("[\"'\\[\\]]", "").toLowerCase();
                if (!t.isBlank() && t.length() <= 24) tags.add(t);
            }
        }
        Matcher d = DOMAIN.matcher(text);
        while (d.find()) tags.add(d.group(1).trim().toLowerCase());
    }
}
