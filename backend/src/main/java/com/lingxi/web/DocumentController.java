package com.lingxi.web;

import com.lingxi.dto.Dtos;
import com.lingxi.model.DocumentEntity;
import com.lingxi.service.KnowledgeService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    private final KnowledgeService knowledgeService;

    public DocumentController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @GetMapping
    public List<Dtos.DocumentDto> list() {
        return knowledgeService.listDocuments();
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Dtos.UploadResponse> upload(@RequestParam("files") MultipartFile[] files) {
        if (files == null || files.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择至少一个文件");
        }
        if (files.length > 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单次最多上传 10 个文件");
        }
        List<Dtos.DocumentDto> accepted = new ArrayList<>();
        List<Dtos.RejectedFile> rejected = new ArrayList<>();
        for (MultipartFile file : files) {
            try {
                accepted.add(knowledgeService.acceptUpload(file));
            } catch (KnowledgeService.DuplicateDocumentException exc) {
                rejected.add(new Dtos.RejectedFile(file.getOriginalFilename(), exc.getMessage()));
            } catch (KnowledgeService.UploadRejectedException exc) {
                rejected.add(new Dtos.RejectedFile(file.getOriginalFilename(), exc.getMessage()));
            } catch (Exception exc) {
                rejected.add(new Dtos.RejectedFile(file.getOriginalFilename(), "创建索引任务失败：" + exc.getMessage()));
            }
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new Dtos.UploadResponse(accepted, rejected));
    }

    @PostMapping("/seed")
    public Dtos.SeedResponse seed(@RequestParam(defaultValue = "false") boolean force) {
        try {
            List<Dtos.DocumentDto> seeded = knowledgeService.seedSamples(force);
            List<Dtos.DocumentDto> documents = knowledgeService.listDocuments();
            String message = seeded.isEmpty() ? "演示资料已存在，无需重复载入" : "已载入 " + seeded.size() + " 篇演示资料";
            return new Dtos.SeedResponse(seeded.size(), message, documents);
        } catch (Exception exc) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "演示资料载入失败：" + exc.getMessage());
        }
    }

    @PostMapping("/{documentId}/reindex")
    public ResponseEntity<Dtos.DocumentDto> reindex(@PathVariable String documentId) {
        try {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(knowledgeService.resetForReindex(documentId));
        } catch (Exception exc) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
        }
    }

    @GetMapping("/{documentId}/chunks")
    public Dtos.ChunksResponse chunks(@PathVariable String documentId) {
        DocumentEntity document = knowledgeService.findDocument(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在"));
        List<Dtos.ChunkDto> chunks = knowledgeService.chunks(documentId);
        return new Dtos.ChunksResponse(toDto(document), chunks, chunks.size());
    }

    @GetMapping("/{documentId}/download")
    public ResponseEntity<Resource> download(@PathVariable String documentId) {
        DocumentEntity document = knowledgeService.findDocument(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在"));
        Path path = Path.of(document.getFilePath());
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "源文件已不存在");
        }
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(document.getMimeType());
        } catch (Exception exc) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(document.getOriginalName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new FileSystemResource(path));
    }

    @DeleteMapping("/{documentId}")
    public Dtos.SimpleResponse delete(@PathVariable String documentId) {
        if (!knowledgeService.deleteDocument(documentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
        }
        return new Dtos.SimpleResponse(true, documentId);
    }

    private Dtos.DocumentDto toDto(DocumentEntity document) {
        return new Dtos.DocumentDto(
                document.getId(), document.getTitle(), document.getOriginalName(), document.getExtension(),
                document.getMimeType(), document.getSizeBytes(), document.getStatus(), document.getError(),
                document.getChunkCount(), document.getCharCount(), document.getPageCount(), document.isDemo(),
                document.getCreatedAt(), document.getUpdatedAt()
        );
    }
}
