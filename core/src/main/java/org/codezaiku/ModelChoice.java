package org.codezaiku;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Which of a server's models to work with. One server often holds several, and not all of them can hold a conversation:
 * a llama-swap or Ollama box usually lists an embedding model beside the chat model, and the list comes back in
 * alphabetical order, so "embed" is first. Setup used to offer the first name as the default. Enter saved an embedding
 * model, and every command after that failed.
 *
 * <p>The name only decides what is OFFERED first. Whether a model can chat is settled by asking it to (setup's one-word
 * test); a name is a guess, and it is used as one.
 */
public final class ModelChoice {

    private ModelChoice() { }

    /** Names of models that turn text into vectors, rank passages, or handle speech and images: not something to talk to. */
    private static final Pattern NOT_CHAT = Pattern.compile(
            "(^|[^a-z0-9])(embed|embedding|embeddings|embedder|bge|gte|e5|minilm|rerank|reranker|colbert|whisper|tts|clip|siglip)([^a-z]|$)"
            + "|text-embedding|nomic-embed|all-mpnet|sentence-t5|snowflake-arctic-embed|jina-(embeddings|reranker|clip)");

    /** Whether a model's name says it cannot chat. */
    public static boolean looksUnableToChat(String id) {
        return id != null && NOT_CHAT.matcher(id.toLowerCase(Locale.ROOT)).find();
    }

    /** The models of {@code ids} worth offering for conversation, in the server's order. All of them when the filter would leave none. */
    public static List<String> chatModels(List<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) if (!looksUnableToChat(id)) out.add(id);
        return out.isEmpty() ? new ArrayList<>(ids) : out;
    }

    /**
     * The model to offer first: the one already in the settings when the server has it, else the first that looks
     * able to chat, else the first. Null for an empty list.
     */
    public static String preferred(List<String> ids, String configured) {
        if (ids == null || ids.isEmpty()) return null;
        if (configured != null && !configured.isBlank() && ids.contains(configured) && !looksUnableToChat(configured)) return configured;
        return chatModels(ids).get(0);
    }
}
