package com.lingxi.rag;

import com.lingxi.config.LingxiProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.DataType;
import io.milvus.grpc.SearchResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.*;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class MilvusVectorStore implements VectorStore, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(MilvusVectorStore.class);
    private final LingxiProperties properties;
    private final MilvusServiceClient client;

    public MilvusVectorStore(LingxiProperties properties) {
        this.properties = properties;
        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withUri(properties.milvusUri())
                .withConnectTimeout(5, TimeUnit.SECONDS);
        if (properties.milvusToken() != null && !properties.milvusToken().isBlank()) {
            builder.withToken(properties.milvusToken());
        }
        this.client = new MilvusServiceClient(builder.build());
        ensureCollection();
    }

    private void ensureCollection() {
        String collection = properties.milvusCollection();
        R<Boolean> has = client.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(collection).build());
        if (has.getStatus() != 0) {
            throw new IllegalStateException("Milvus hasCollection failed: " + has.getMessage());
        }
        if (!Boolean.TRUE.equals(has.getData())) {
            FieldType id = FieldType.newBuilder()
                    .withName("id").withDataType(DataType.VarChar).withPrimaryKey(true).withMaxLength(64).build();
            FieldType documentId = FieldType.newBuilder()
                    .withName("document_id").withDataType(DataType.VarChar).withMaxLength(64).build();
            FieldType vector = FieldType.newBuilder()
                    .withName("vector").withDataType(DataType.FloatVector)
                    .withDimension(properties.embeddingDim()).build();
            R<?> created = client.createCollection(CreateCollectionParam.newBuilder()
                    .withCollectionName(collection)
                    .withFieldTypes(List.of(id, documentId, vector))
                    .withConsistencyLevel(ConsistencyLevelEnum.BOUNDED)
                    .build());
            requireOk(created, "createCollection");
            requireOk(client.createIndex(CreateIndexParam.newBuilder()
                    .withCollectionName(collection)
                    .withFieldName("vector")
                    .withIndexType(IndexType.IVF_FLAT)
                    .withMetricType(MetricType.COSINE)
                    .withExtraParam("{\"nlist\":128}")
                    .withSyncMode(Boolean.TRUE)
                    .build()), "create vector index");
            try {
                client.createIndex(CreateIndexParam.newBuilder()
                        .withCollectionName(collection)
                        .withFieldName("document_id")
                        .withIndexType(IndexType.INVERTED)
                        .withSyncMode(Boolean.TRUE)
                        .build());
            } catch (Exception exc) {
                log.warn("Could not create Milvus scalar index: {}", exc.getMessage());
            }
        }
        R<?> loaded = client.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(collection).withSyncLoad(true).build());
        requireOk(loaded, "loadCollection");
        log.info("Connected to Milvus at {} collection={}", properties.milvusUri(), collection);
    }

    @Override
    public String name() {
        return "Milvus";
    }

    @Override
    public void add(List<VectorRecord> records) {
        if (records.isEmpty()) return;
        List<String> ids = new ArrayList<>();
        List<String> documents = new ArrayList<>();
        List<List<Float>> vectors = new ArrayList<>();
        for (VectorRecord record : records) {
            ids.add(record.chunkId());
            documents.add(record.documentId());
            vectors.add(toFloats(record.embedding()));
        }
        R<?> inserted = client.insert(InsertParam.newBuilder()
                .withCollectionName(properties.milvusCollection())
                .withFields(List.of(
                        new InsertParam.Field("id", ids),
                        new InsertParam.Field("document_id", documents),
                        new InsertParam.Field("vector", vectors)
                ))
                .build());
        requireOk(inserted, "insert");
        requireOk(client.flush(FlushParam.newBuilder()
                .withCollectionNames(List.of(properties.milvusCollection())).build()), "flush");
    }

    @Override
    public void deleteDocument(String documentId) {
        if (documentId == null || !documentId.replace("-", "").chars().allMatch(Character::isLetterOrDigit)) return;
        R<?> deleted = client.delete(DeleteParam.newBuilder()
                .withCollectionName(properties.milvusCollection())
                .withExpr("document_id == \"" + documentId + "\"")
                .build());
        requireOk(deleted, "delete vectors");
    }

    @Override
    public List<VectorHit> search(double[] embedding, int limit) {
        try {
            List<List<Float>> vectors = List.of(toFloats(embedding));
            R<SearchResults> response = client.search(SearchParam.newBuilder()
                    .withCollectionName(properties.milvusCollection())
                    .withMetricType(MetricType.COSINE)
                    .withVectorFieldName("vector")
                    .withFloatVectors(vectors)
                    .withTopK(limit)
                    .withOutFields(List.of("document_id"))
                    .withParams("{\"nprobe\":16}")
                    .withConsistencyLevel(ConsistencyLevelEnum.BOUNDED)
                    .build());
            if (response.getStatus() != 0) {
                log.warn("Milvus search failed: {}", response.getMessage());
                return List.of();
            }
            SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
            List<VectorHit> hits = new ArrayList<>();
            for (SearchResultsWrapper.IDScore score : wrapper.getIDScore(0)) {
                hits.add(new VectorHit(score.getStrID(), score.getScore()));
            }
            return hits;
        } catch (Exception exc) {
            log.warn("Milvus search error: {}", exc.getMessage());
            return List.of();
        }
    }

    @Override
    public long count() {
        try {
            R<io.milvus.grpc.GetCollectionStatisticsResponse> stats =
                    client.getCollectionStatistics(GetCollectionStatisticsParam.newBuilder()
                            .withCollectionName(properties.milvusCollection()).build());
            if (stats.getStatus() != 0 || stats.getData() == null) return 0;
            for (io.milvus.grpc.KeyValuePair pair : stats.getData().getStatsList()) {
                if ("row_count".equals(pair.getKey())) {
                    try {
                        return Long.parseLong(pair.getValue());
                    } catch (NumberFormatException ignored) {
                        return 0;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    @Override
    public boolean healthy() {
        try {
            R<?> health = client.checkHealth();
            return health.getStatus() == 0;
        } catch (Exception exc) {
            return false;
        }
    }

    @Override
    public void close() {
        try {
            client.close(5);
        } catch (Exception ignored) {
        }
    }

    private static List<Float> toFloats(double[] values) {
        List<Float> result = new ArrayList<>(values.length);
        for (double value : values) result.add((float) value);
        return result;
    }

    private static void requireOk(R<?> result, String operation) {
        if (result.getStatus() != 0) {
            throw new IllegalStateException("Milvus " + operation + " failed: " + result.getMessage());
        }
    }
}
