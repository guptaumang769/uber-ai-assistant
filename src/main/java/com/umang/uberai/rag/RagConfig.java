package com.umang.uberai.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.StreamUtils;

/**
 * RETRIEVAL-AUGMENTED GENERATION (RAG) setup.
 *
 * <p>RAG lets the agent answer help/policy questions ("what's the cancellation policy?") from a curated
 * knowledge base instead of hallucinating. At startup we:</p>
 * <ol>
 *   <li>read the FAQ/policy markdown docs under {@code src/main/resources/docs/},</li>
 *   <li>embed each into a vector using an {@link EmbeddingModel},</li>
 *   <li>store them in an in-memory {@link SimpleVectorStore}.</li>
 * </ol>
 * <p>At query time, {@code QuestionAnswerAdvisor} (wired in ChatClientConfig) embeds the user's question,
 * runs a similarity search over this store, and injects the top matches into the prompt so the LLM
 * answers grounded in real content. In the key-free demo, the mock model queries this same store directly.</p>
 */
@Configuration
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    /**
     * A deterministic, offline embedding model so RAG works with no API key. Backs off automatically if
     * a real {@link EmbeddingModel} (e.g. from a provider starter) is configured.
     */
    @Bean
    @ConditionalOnMissingBean(EmbeddingModel.class)
    public EmbeddingModel mockEmbeddingModel() {
        log.info("No real EmbeddingModel configured — using deterministic MockEmbeddingModel for RAG (key-free).");
        return new MockEmbeddingModel();
    }

    /** In-memory vector store, seeded from the FAQ/policy docs at startup. */
    @Bean
    public VectorStore faqVectorStore(EmbeddingModel embeddingModel) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        List<Document> docs = loadFaqDocs();
        if (!docs.isEmpty()) {
            store.add(docs);
        }
        log.info("Seeded RAG vector store with {} FAQ/policy document(s).", docs.size());
        return store;
    }

    private List<Document> loadFaqDocs() {
        List<Document> docs = new ArrayList<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:/docs/*.md");
            for (Resource r : resources) {
                try {
                    String content = StreamUtils.copyToString(r.getInputStream(), StandardCharsets.UTF_8);
                    docs.add(new Document(content, Map.of("source", r.getFilename())));
                } catch (IOException e) {
                    log.warn("Could not read FAQ doc {}: {}", r.getFilename(), e.toString());
                }
            }
        } catch (IOException e) {
            log.warn("Could not scan classpath:/docs/*.md: {}", e.toString());
        }
        return docs;
    }
}
