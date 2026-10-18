package com.lingxi.rag;

import com.lingxi.model.ChunkEntity;
import com.lingxi.repository.ChunkRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class LocalVectorStore implements VectorStore {
    private final ChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile List<CachedVector> cache = List.of();
    private volatile long cacheKey = -1;

    public LocalVectorStore(ChunkRepository chunkRepository, EmbeddingService embeddingService) {
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
    }

    @Override
    public String name() {
        return "本地向量索引";
    }

    @Override
    public void add(List<VectorRecord> records) {
        invalidate();
    }

    @Override
    public void deleteDocument(String documentId) {
        invalidate();
    }

    @Override
    public List<VectorHit> search(double[] embedding, int limit) {
        List<CachedVector> vectors = loadCache();
        List<VectorHit> hits = new ArrayList<>();
        for (CachedVector vector : vectors) {
            hits.add(new VectorHit(vector.chunkId(), embeddingService.cosine(embedding, vector.embedding())));
        }
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        return hits.subList(0, Math.min(limit, hits.size()));
    }

    @Override
    public long count() {
        return chunkRepository.count();
    }

    @Override
    public boolean healthy() {
        return true;
    }

    public void invalidate() {
        lock.lock();
        try {
            cacheKey = -1;
            cache = List.of();
        } finally {
            lock.unlock();
        }
    }

    private List<CachedVector> loadCache() {
        long count = chunkRepository.count();
        if (count == cacheKey && !cache.isEmpty()) return cache;
        lock.lock();
        try {
            count = chunkRepository.count();
            if (count == cacheKey && !cache.isEmpty()) return cache;
            List<CachedVector> vectors = new ArrayList<>();
            for (ChunkEntity chunk : chunkRepository.findAll()) {
                vectors.add(new CachedVector(chunk.getId(), embeddingService.deserialize(chunk.getEmbedding())));
            }
            cache = List.copyOf(vectors);
            cacheKey = count;
            return cache;
        } finally {
            lock.unlock();
        }
    }

    private record CachedVector(String chunkId, double[] embedding) {
    }
}
