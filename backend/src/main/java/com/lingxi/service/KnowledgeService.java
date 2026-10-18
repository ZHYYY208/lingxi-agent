package com.lingxi.service;

import com.lingxi.config.LingxiProperties;
import com.lingxi.dto.Dtos;
import com.lingxi.model.ChunkEntity;
import com.lingxi.model.DocumentEntity;
import com.lingxi.rag.DocumentLoader;
import com.lingxi.rag.EmbeddingService;
import com.lingxi.rag.TextTools;
import com.lingxi.rag.VectorStore;
import com.lingxi.repository.ChatMessageRepository;
import com.lingxi.repository.ChatSessionRepository;
import com.lingxi.repository.ChunkRepository;
import com.lingxi.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

@Service
public class KnowledgeService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);
    private static final List<String> DEMO_FILES = List.of(
            "01_员工手册.md", "02_灵犀产品技术白皮书.md", "03_2026Q3经营分析简报.md", "04_客户服务与安全合规FAQ.md"
    );
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".pdf", ".docx", ".md", ".markdown", ".txt");

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final DocumentLoader documentLoader;
    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    private final TextTools textTools;
    private final LingxiProperties properties;
    private final DtoMapper mapper;
    private final TaskExecutor documentExecutor;
    private final TransactionTemplate transactionTemplate;

    public KnowledgeService(
            DocumentRepository documentRepository,
            ChunkRepository chunkRepository,
            ChatSessionRepository sessionRepository,
            ChatMessageRepository messageRepository,
            DocumentLoader documentLoader,
            EmbeddingService embeddingService,
            VectorStore vectorStore,
            TextTools textTools,
            LingxiProperties properties,
            DtoMapper mapper,
            @Qualifier("documentExecutor") TaskExecutor documentExecutor,
            PlatformTransactionManager transactionManager
    ) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.documentLoader = documentLoader;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.textTools = textTools;
        this.properties = properties;
        this.mapper = mapper;
        this.documentExecutor = documentExecutor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        ensureDirectories();
    }

    public void ensureDirectories() {
        try {
            Files.createDirectories(properties.dataPath());
            Files.createDirectories(properties.uploadsPath());
            Files.createDirectories(properties.dataPath().resolve("samples"));
        } catch (IOException exc) {
            throw new IllegalStateException("无法创建数据目录", exc);
        }
    }

    public List<Dtos.DocumentDto> listDocuments() {
        return documentRepository.findAllByOrderByCreatedAtDesc().stream().map(mapper::document).toList();
    }

    public Optional<DocumentEntity> findDocument(String id) {
        return documentRepository.findById(id);
    }

    public Dtos.DocumentDto acceptUpload(MultipartFile file) {
        String originalName = safeOriginalName(file.getOriginalFilename());
        String extension = extensionOf(originalName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new UploadRejectedException("不支持该格式，仅支持 PDF / DOCX / Markdown / TXT");
        }
        if (file.isEmpty()) throw new UploadRejectedException("文件为空");
        if (file.getSize() > properties.maxUploadBytes()) {
            throw new UploadRejectedException("文件超过 " + properties.maxUploadMb() + " MB 限制");
        }

        String storedName = UUID.randomUUID().toString().replace("-", "") + extension;
        Path target = properties.uploadsPath().resolve(storedName);
        String hash;
        try (InputStream input = file.getInputStream()) {
            hash = writeAndHash(input, target);
        } catch (Exception exc) {
            deleteQuietly(target);
            throw new UploadRejectedException("保存文件失败：" + exc.getMessage());
        }

        Optional<DocumentEntity> duplicate = documentRepository.findFirstByContentHashAndStatusIn(
                hash, List.of("processing", "ready")
        );
        if (duplicate.isPresent()) {
            deleteQuietly(target);
            throw new DuplicateDocumentException(duplicate.get().getTitle());
        }

        DocumentEntity document = new DocumentEntity();
        document.setFilename(storedName);
        document.setOriginalName(originalName);
        document.setExtension(extension);
        String mimeType = file.getContentType();
        if (mimeType == null || mimeType.isBlank()) {
            mimeType = URLConnection.guessContentTypeFromName(originalName);
        }
        document.setMimeType(mimeType == null || mimeType.isBlank() ? "application/octet-stream" : mimeType);
        document.setSizeBytes(file.getSize());
        document.setContentHash(hash);
        document.setTitle(stripExtension(originalName));
        document.setStatus("processing");
        document.setFilePath(target.toString());
        document.setDemo(false);
        documentRepository.save(document);
        submitProcessing(document.getId());
        return mapper.document(document);
    }

    public void submitProcessing(String documentId) {
        documentExecutor.execute(() -> processDocument(documentId));
    }

    public void processDocument(String documentId) {
        DocumentEntity document = documentRepository.findById(documentId).orElse(null);
        if (document == null) return;
        long started = System.currentTimeMillis();
        try {
            Path path = Path.of(document.getFilePath());
            if (!Files.exists(path)) throw new DocumentLoader.DocumentLoadException("源文件不存在，请重新上传");
            DocumentLoader.LoadedDocument loaded = documentLoader.load(path, document.getOriginalName());

            List<Piece> pieces = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int ordinal = 0;
            for (DocumentLoader.Block block : loaded.blocks()) {
                for (String chunk : textTools.chunkText(block.text(), properties.chunkSize(), properties.chunkOverlap())) {
                    String cleaned = textTools.clean(chunk);
                    if (cleaned.isBlank() || seen.contains(cleaned)) continue;
                    seen.add(cleaned);
                    pieces.add(new Piece(ordinal++, cleaned, block.section(), block.page()));
                }
            }
            if (pieces.isEmpty()) throw new DocumentLoader.DocumentLoadException("文档中没有生成有效文本片段");

            List<String> embeddingInputs = pieces.stream()
                    .map(piece -> loaded.title() + "\n" + nullToEmpty(piece.section()) + "\n" + piece.text())
                    .toList();
            List<double[]> vectors = embeddingService.embedAll(embeddingInputs);

            List<ChunkEntity> chunks = new ArrayList<>();
            for (int i = 0; i < pieces.size(); i++) {
                Piece piece = pieces.get(i);
                ChunkEntity chunk = new ChunkEntity();
                chunk.setDocumentId(documentId);
                chunk.setOrdinal(piece.ordinal());
                chunk.setText(piece.text());
                chunk.setEmbedding(embeddingService.serialize(vectors.get(i)));
                chunk.setPage(piece.page());
                chunk.setSection(piece.section() == null ? "" : truncate(piece.section(), 500));
                chunk.setCharCount(piece.text().length());
                chunks.add(chunk);
            }

            transactionTemplate.executeWithoutResult(status -> {
                DocumentEntity current = documentRepository.findById(documentId)
                        .orElseThrow(() -> new IllegalStateException("文档不存在"));
                chunkRepository.deleteByDocumentId(documentId);
                chunkRepository.saveAll(chunks);
                current.setTitle(truncate(loaded.title(), 500));
                current.setChunkCount(chunks.size());
                current.setCharCount(loaded.charCount());
                current.setPageCount(loaded.pageCount());
                current.setStatus("ready");
                current.setError(null);
                documentRepository.save(current);
            });

            List<VectorStore.VectorRecord> records = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                records.add(new VectorStore.VectorRecord(chunks.get(i).getId(), documentId, vectors.get(i)));
            }
            try {
                vectorStore.deleteDocument(documentId);
                vectorStore.add(records);
            } catch (Exception exc) {
                log.warn("Vector store update failed, local retrieval remains available: {}", exc.getMessage());
            }
            log.info("Indexed document id={} title={} chunks={} elapsed={}ms",
                    documentId, loaded.title(), chunks.size(), System.currentTimeMillis() - started);
        } catch (Exception exc) {
            log.error("Failed to process document {}: {}", documentId, exc.getMessage());
            transactionTemplate.executeWithoutResult(status -> documentRepository.findById(documentId).ifPresent(current -> {
                current.setStatus("failed");
                current.setError(truncate(exc.getMessage() == null ? exc.getClass().getSimpleName() : exc.getMessage(), 2000));
                current.setChunkCount(0);
                documentRepository.save(current);
            }));
        }
    }

    public Dtos.DocumentDto resetForReindex(String documentId) {
        DocumentEntity document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NoSuchElementException("文档不存在"));
        document.setStatus("processing");
        document.setError(null);
        documentRepository.save(document);
        submitProcessing(documentId);
        return mapper.document(document);
    }

    public boolean deleteDocument(String documentId) {
        DocumentEntity document = documentRepository.findById(documentId).orElse(null);
        if (document == null) return false;
        Path file = Path.of(document.getFilePath());
        transactionTemplate.executeWithoutResult(status -> {
            chunkRepository.deleteByDocumentId(documentId);
            documentRepository.deleteById(documentId);
        });
        try {
            vectorStore.deleteDocument(documentId);
        } catch (Exception exc) {
            log.warn("Failed to delete vectors for {}: {}", documentId, exc.getMessage());
        }
        deleteQuietly(file);
        return true;
    }

    public List<Dtos.ChunkDto> chunks(String documentId) {
        return chunkRepository.findByDocumentIdOrderByOrdinal(documentId).stream()
                .map(chunk -> new Dtos.ChunkDto(chunk.getId(), chunk.getOrdinal(), chunk.getText(),
                        chunk.getSection(), chunk.getPage(), chunk.getCharCount()))
                .toList();
    }

    public Dtos.Stats stats() {
        long documentCount = documentRepository.count();
        long ready = documentRepository.countByStatus("ready");
        long chunkCount = chunkRepository.count();
        long charCount = chunkRepository.sumCharCount();
        long sessionCount = sessionRepository.count();
        long messageCount = messageRepository.count();
        Instant lastIndexed = documentRepository.findLastReadyUpdatedAt();
        return new Dtos.Stats(documentCount, ready, chunkCount, charCount, sessionCount, messageCount,
                lastIndexed, documentCount == 0 ? 0 : Math.round(ready * 10000.0 / documentCount) / 10000.0);
    }

    public List<Dtos.DocumentDto> seedSamples(boolean force) {
        List<DocumentEntity> existingDemo = documentRepository.findAll().stream().filter(DocumentEntity::isDemo).toList();
        if (!force && !existingDemo.isEmpty()) return List.of();
        if (force) {
            for (DocumentEntity demo : existingDemo) {
                deleteDocument(demo.getId());
            }
        }

        List<Dtos.DocumentDto> seeded = new ArrayList<>();
        for (String name : DEMO_FILES) {
            try {
                byte[] content = readDemoResource(name);
                String hash = sha256(content);
                if (documentRepository.findFirstByContentHashAndStatusIn(hash, List.of("processing", "ready")).isPresent()) {
                    continue;
                }
                Path sampleDir = properties.dataPath().resolve("samples");
                Files.createDirectories(sampleDir);
                Path path = sampleDir.resolve(name);
                Files.write(path, content);
                DocumentEntity document = new DocumentEntity();
                document.setFilename(name);
                document.setOriginalName(name);
                document.setExtension(extensionOf(name));
                document.setMimeType("text/markdown");
                document.setSizeBytes(content.length);
                document.setContentHash(hash);
                document.setTitle(stripExtension(name));
                document.setStatus("processing");
                document.setDemo(true);
                document.setFilePath(path.toString());
                documentRepository.save(document);
                processDocument(document.getId());
                documentRepository.findById(document.getId()).ifPresent(current -> seeded.add(mapper.document(current)));
            } catch (Exception exc) {
                log.warn("Could not seed demo document {}: {}", name, exc.getMessage());
            }
        }
        return seeded;
    }

    private byte[] readDemoResource(String name) throws IOException {
        ClassPathResource resource = new ClassPathResource("samples/" + name);
        if (resource.exists()) {
            try (InputStream input = resource.getInputStream()) {
                return input.readAllBytes();
            }
        }
        Path fallback = Path.of("samples", name);
        if (Files.exists(fallback)) return Files.readAllBytes(fallback);
        throw new IOException("缺少演示资料：" + name);
    }

    private String writeAndHash(InputStream input, Path target) throws Exception {
        Files.createDirectories(target.getParent());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            long total = 0;
            while ((read = input.read(buffer)) > 0) {
                total += read;
                if (total > properties.maxUploadBytes()) {
                    throw new UploadRejectedException("文件超过 " + properties.maxUploadMb() + " MB 限制");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private static String safeOriginalName(String name) {
        String raw = name == null ? "未命名文档" : name.replace('\\', '/');
        String base = Path.of(raw).getFileName().toString().strip();
        base = base.replaceAll("[\\p{Cntrl}\"'<>|:]", "");
        if (base.isBlank()) base = "未命名文档";
        return base.length() > 180 ? base.substring(0, 180) : base;
    }

    private static String extensionOf(String name) {
        int index = name.lastIndexOf('.');
        return index >= 0 ? name.substring(index).toLowerCase(Locale.ROOT) : "";
    }

    private static String stripExtension(String name) {
        return name.replaceFirst("\\.[^.]+$", "");
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private record Piece(int ordinal, String text, String section, Integer page) {
    }

    public static class DuplicateDocumentException extends RuntimeException {
        public DuplicateDocumentException(String existingTitle) {
            super("内容重复，已存在于知识库：" + existingTitle);
        }
    }

    public static class UploadRejectedException extends RuntimeException {
        public UploadRejectedException(String message) {
            super(message);
        }
    }
}
