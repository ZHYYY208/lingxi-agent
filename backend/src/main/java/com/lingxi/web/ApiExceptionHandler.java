package com.lingxi.web;

import com.lingxi.dto.Dtos;
import com.lingxi.security.RequestLoggingFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Dtos.ErrorResponse> handleStatus(ResponseStatusException exc, HttpServletRequest request) {
        return ResponseEntity.status(exc.getStatusCode())
                .body(error(exc.getReason() == null ? "请求失败" : exc.getReason(),
                        statusCode(exc.getStatusCode().value()), request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Dtos.ErrorResponse> handleValidation(MethodArgumentNotValidException exc,
                                                               HttpServletRequest request) {
        String detail = exc.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(field -> field.getField() + "：" + field.getDefaultMessage())
                .orElse("请求参数校验失败");
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(error(detail, "VALIDATION_ERROR", request));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Dtos.ErrorResponse> handleMissingUpload(MissingServletRequestPartException exc,
                                                                   HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(error("缺少上传文件", "UPLOAD_ERROR", request));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Dtos.ErrorResponse> handleUploadSize(MaxUploadSizeExceededException exc,
                                                               HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(error("上传文件超过大小限制", "UPLOAD_ERROR", request));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Dtos.ErrorResponse> handleNotFound(NoSuchElementException exc, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error(exc.getMessage() == null ? "资源不存在" : exc.getMessage(), "NOT_FOUND", request));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Dtos.ErrorResponse> handleMissingResource(NoResourceFoundException exc,
                                                                    HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error("资源不存在", "NOT_FOUND", request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Dtos.ErrorResponse> handleGeneric(Exception exc, HttpServletRequest request) {
        log.error("Unhandled application error", exc);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error("服务内部错误，请稍后重试", "INTERNAL_ERROR", request));
    }

    private Dtos.ErrorResponse error(String detail, String code, HttpServletRequest request) {
        Object id = request.getAttribute(RequestLoggingFilter.REQUEST_ID_ATTRIBUTE);
        return new Dtos.ErrorResponse(detail, code, id == null ? "" : id.toString());
    }

    private static String statusCode(int status) {
        return switch (status) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 422 -> "VALIDATION_ERROR";
            case 429 -> "RATE_LIMITED";
            default -> "REQUEST_FAILED";
        };
    }
}
