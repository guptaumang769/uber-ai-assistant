package com.umang.uberai.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * A tiny, deterministic, KEY-FREE {@link org.springframework.ai.embedding.EmbeddingModel}.
 *
 * <p>{@link org.springframework.ai.vectorstore.SimpleVectorStore} needs an EmbeddingModel to turn text
 * into vectors for similarity search. A real one (OpenAI/Anthropic) needs an API key; to keep RAG
 * working with zero keys we build a fixed-dimension "bag of words" hashing embedding: each token is
 * hashed into one of {@code DIM} buckets and its count accumulated, then the vector is L2-normalized.
 * Cosine similarity over these vectors approximates term-overlap relevance — good enough to retrieve
 * the right FAQ passage for a question, which is all the demo needs.</p>
 *
 * <p>Not semantic like a neural embedding, but fully offline and deterministic. When a real embedding
 * model is configured it would replace this bean.</p>
 */
public class MockEmbeddingModel implements org.springframework.ai.embedding.EmbeddingModel {

    private static final int DIM = 512;
    private static final Pattern TOKEN = Pattern.compile("[^a-z0-9]+");

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> out = new ArrayList<>();
        List<String> inputs = request.getInstructions();
        for (int i = 0; i < inputs.size(); i++) {
            out.add(new Embedding(embedText(inputs.get(i)), i));
        }
        return new EmbeddingResponse(out);
    }

    @Override
    public float[] embed(Document document) {
        return embedText(document.getText());
    }

    @Override
    public int dimensions() {
        return DIM;
    }

    private float[] embedText(String text) {
        float[] vec = new float[DIM];
        if (text == null || text.isBlank()) {
            vec[0] = 1f; // avoid an all-zero vector (undefined cosine)
            return vec;
        }
        for (String token : TOKEN.split(text.toLowerCase())) {
            if (token.isBlank()) {
                continue;
            }
            int bucket = Math.floorMod(token.hashCode(), DIM);
            vec[bucket] += 1f;
        }
        // L2 normalize so cosine similarity == dot product.
        double norm = 0;
        for (float v : vec) {
            norm += (double) v * v;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < DIM; i++) {
                vec[i] = (float) (vec[i] / norm);
            }
        } else {
            vec[0] = 1f;
        }
        return vec;
    }
}
