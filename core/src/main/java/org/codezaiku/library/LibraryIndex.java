package org.codezaiku.library;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Read-only reader over the framework-knowledge Library — the Lucene {@code codezaiku_library}
 * collection at {@code ~/.codezaiku/ocean/library} (BM25 text + HNSW dense), populated from OPDS-CK
 * packs (DevDocs etc.). This is the DATA layer the spec (SPEC_CODEZAIKU_LIBRARY.md) always intended;
 * the reset dropped the code but left the index on disk.
 *
 * <p>Deliberately minimal and READ-ONLY: the push loop (harness queries, injects — never the model
 * pulling) only needs retrieval. The 553-line write-heavy {@code LuceneVectorStore} from pre-focus is
 * not ported. Field names + analyzer match what the index was written with (Lucene 10.4).
 *
 * <p>Graceful when the index is absent: {@link #available()} is false and {@link #search} returns
 * empty, so the harness runs unchanged on boxes without a loaded library.
 */
public final class LibraryIndex implements AutoCloseable {

    // Schema written by LuceneVectorStore (pre-focus) — must match for retrieval to work.
    private static final String FIELD_CONTENT = "content";              // analyzed, BM25
    private static final String FIELD_CONTENT_STORED = "content_stored"; // stored, returned
    private static final String FIELD_FRAMEWORK = "framework";          // StringField, exact-match filter
    private static final String FIELD_SOURCE_URL = "source_url";
    private static final String FIELD_TITLE = "title";
    private static final String FIELD_VERSION = "version";

    private final DirectoryReader reader; // nullable when no index
    private final IndexSearcher searcher; // nullable when no index
    private final StandardAnalyzer analyzer = new StandardAnalyzer();

    /** One retrieved chunk: the reference text plus provenance. */
    public record Chunk(String content, String framework, String title, String version,
                        String sourceUrl, float score) {}

    /** Open the Library index at {@code collectionDir} (e.g. ~/.codezaiku/ocean/library). */
    public LibraryIndex(Path collectionDir) {
        DirectoryReader r = null;
        try {
            Directory dir = FSDirectory.open(collectionDir);
            if (DirectoryReader.indexExists(dir)) {
                r = DirectoryReader.open(dir); // read-only — does not take the write.lock
            } else {
                dir.close();
            }
        } catch (Exception e) {
            r = null; // absent / unreadable index → no-op reader
        }
        this.reader = r;
        this.searcher = r == null ? null : new IndexSearcher(r);
    }

    /** True when a real index was opened. */
    public boolean available() {
        return searcher != null;
    }

    /** Number of indexed chunks (0 when unavailable). */
    public int size() {
        return reader == null ? 0 : reader.numDocs();
    }

    /** BM25 search, no framework filter. */
    public List<Chunk> search(String queryText, int topK) {
        return search(queryText, (Collection<String>) null, topK);
    }

    /** BM25 search filtered to one framework (convenience for the smoke CLI). */
    public List<Chunk> search(String queryText, String framework, int topK) {
        return search(queryText,
                (framework == null || framework.isBlank()) ? null : List.of(framework), topK);
    }

    /**
     * BM25 search over the reference content, optionally filtered to a SET of {@code frameworks}
     * (exact-match on the StringField — e.g. {@code [godot]} or {@code [python, fastapi]}). Top-k
     * chunks, best first; empty when unavailable. The set-filter keeps a rust task off pytorch and
     * lets a Python+FastAPI task pull from both its packs.
     */
    public List<Chunk> search(String queryText, Collection<String> frameworks, int topK) {
        return doSearch(queryText, frameworks, null, topK);
    }

    /**
     * Search restricted to one {@code trustTier} (e.g. {@code evergreen-idiom}). Used to GUARANTEE the
     * curated idioms surface in the push alongside API reference, instead of competing with — and
     * losing to — bulk DevDocs pages for generic queries.
     */
    public List<Chunk> searchTier(String queryText, Collection<String> frameworks,
                                  String trustTier, int topK) {
        return doSearch(queryText, frameworks, trustTier, topK);
    }

    private List<Chunk> doSearch(String queryText, Collection<String> frameworks,
                                 String trustTier, int topK) {
        if (searcher == null || queryText == null || queryText.isBlank()) return List.of();
        try {
            QueryParser parser = new QueryParser(FIELD_CONTENT, analyzer);
            Query parsed = parser.parse(QueryParser.escape(queryText));
            // Boost curated idioms — but only when NOT already filtering to a tier (no point then).
            Query content = (trustTier != null) ? parsed : new BooleanQuery.Builder()
                    .add(parsed, BooleanClause.Occur.MUST)
                    .add(new BoostQuery(new TermQuery(new Term("trust_tier", "evergreen-idiom")), 4.0f),
                            BooleanClause.Occur.SHOULD)
                    .build();
            BooleanQuery.Builder b = new BooleanQuery.Builder().add(content, BooleanClause.Occur.MUST);
            boolean wrapped = false;
            if (frameworks != null && !frameworks.isEmpty()) {
                BooleanQuery.Builder fw = new BooleanQuery.Builder();
                for (String f : frameworks) {
                    if (f != null && !f.isBlank()) {
                        fw.add(new TermQuery(new Term(FIELD_FRAMEWORK, f.trim().toLowerCase())),
                                BooleanClause.Occur.SHOULD);
                    }
                }
                b.add(fw.build(), BooleanClause.Occur.FILTER);
                wrapped = true;
            }
            if (trustTier != null) {
                b.add(new TermQuery(new Term("trust_tier", trustTier)), BooleanClause.Occur.FILTER);
                wrapped = true;
            }
            Query query = wrapped ? b.build() : content;
            TopDocs hits = searcher.search(query, Math.max(1, topK));
            StoredFields stored = searcher.storedFields();
            List<Chunk> out = new ArrayList<>();
            for (ScoreDoc sd : hits.scoreDocs) {
                Document d = stored.document(sd.doc);
                String text = d.get(FIELD_CONTENT_STORED);
                if (text == null) text = d.get(FIELD_CONTENT);
                out.add(new Chunk(text == null ? "" : text,
                        d.get(FIELD_FRAMEWORK), d.get(FIELD_TITLE), d.get(FIELD_VERSION),
                        d.get(FIELD_SOURCE_URL), sd.score));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Debug: stored field names+values of the first matching doc (to discover the schema). */
    public String describeFields(String queryText) {
        if (searcher == null) return "(no index)";
        try {
            QueryParser parser = new QueryParser(FIELD_CONTENT, analyzer);
            var query = parser.parse(QueryParser.escape(queryText));
            TopDocs hits = searcher.search(query, 1);
            if (hits.scoreDocs.length == 0) return "(no hits)";
            Document d = searcher.storedFields().document(hits.scoreDocs[0].doc);
            StringBuilder sb = new StringBuilder();
            for (var f : d.getFields()) {
                String v = f.stringValue();
                if (v != null && v.length() > 80) v = v.substring(0, 80) + "…";
                sb.append(f.name()).append(" = ").append(v).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "(error: " + e + ")";
        }
    }

    @Override
    public void close() {
        try {
            if (reader != null) reader.close();
        } catch (Exception ignored) {
            // best-effort
        }
    }
}
