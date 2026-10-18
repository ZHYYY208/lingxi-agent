package com.lingxi.config;

import com.lingxi.rag.LocalVectorStore;
import com.lingxi.rag.MilvusVectorStore;
import com.lingxi.rag.VectorStore;
import com.lingxi.repository.ChunkRepository;
import com.lingxi.rag.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class VectorStoreConfig {
    private static final Logger log = LoggerFactory.getLogger(VectorStoreConfig.class);

    @Bean
    public LocalVectorStore localVectorStore(ChunkRepository chunkRepository, EmbeddingService embeddingService) {
        return new LocalVectorStore(chunkRepository, embeddingService);
    }

    @Bean
    @Primary
    public VectorStore vectorStore(LingxiProperties properties, LocalVectorStore localVectorStore) {
        if (properties.milvusUri() != null && !properties.milvusUri().isBlank()) {
            try {
                return new MilvusVectorStore(properties);
            } catch (Exception exc) {
                log.warn("Milvus unavailable ({}); falling back to local vector index", exc.getMessage());
            }
        }
        return localVectorStore;
    }
}
