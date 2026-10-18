package com.lingxi.rag;

import java.util.List;

public interface VectorStore {
    String name();

    void add(List<VectorRecord> records);

    void deleteDocument(String documentId);

    List<VectorHit> search(double[] embedding, int limit);

    long count();

    boolean healthy();

    record VectorRecord(String chunkId, String documentId, double[] embedding) {
    }

    record VectorHit(String chunkId, double score) {
    }
}
