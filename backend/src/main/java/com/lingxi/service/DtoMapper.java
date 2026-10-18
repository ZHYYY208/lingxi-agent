package com.lingxi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingxi.dto.Dtos;
import com.lingxi.model.ChatMessageEntity;
import com.lingxi.model.DocumentEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DtoMapper {
    private final ObjectMapper mapper;

    public DtoMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Dtos.DocumentDto document(DocumentEntity doc) {
        return new Dtos.DocumentDto(
                doc.getId(), doc.getTitle(), doc.getOriginalName(), doc.getExtension(), doc.getMimeType(),
                doc.getSizeBytes(), doc.getStatus(), doc.getError(), doc.getChunkCount(), doc.getCharCount(),
                doc.getPageCount(), doc.isDemo(), doc.getCreatedAt(), doc.getUpdatedAt()
        );
    }

    public Dtos.MessageDto message(ChatMessageEntity message) {
        List<Dtos.Citation> citations = new ArrayList<>();
        List<Dtos.ToolTrace> tools = new ArrayList<>();
        try {
            citations = mapper.readValue(message.getCitationsJson(), new TypeReference<>() {
            });
        } catch (Exception ignored) {
        }
        try {
            tools = mapper.readValue(message.getToolsJson(), new TypeReference<>() {
            });
        } catch (Exception ignored) {
        }
        return new Dtos.MessageDto(
                message.getId(), message.getRole(), message.getContent(), citations, tools,
                message.getLatencyMs(), message.getCreatedAt()
        );
    }
}
