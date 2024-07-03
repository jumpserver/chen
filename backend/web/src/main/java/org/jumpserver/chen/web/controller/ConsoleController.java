package org.jumpserver.chen.web.controller;

import org.jumpserver.chen.framework.i18n.MessageUtils;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.web.entity.UploadResponse;
import org.jumpserver.chen.web.exception.ChenException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/console")
public class ConsoleController {

    @GetMapping("/export/{fileKey}")
    public ResponseEntity<Resource> exportData(@PathVariable String fileKey) {
        if (!SessionManager.getCurrentSession().canDownload()) {
            throw new ChenException(MessageUtils.get("msg.error.no_permission"));
        }
        var path = SessionManager.getCurrentSession().getTempPath();
        Resource resource = new FileSystemResource(path.resolve(fileKey).toFile());
        var resp = ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-disposition", String.format("attachment; filename=%s", fileKey))
                .body(resource);
        return resp;
    }

    @PostMapping("/upload")
    public UploadResponse uploadData(@RequestParam("file") MultipartFile file) {
        if (!SessionManager.getCurrentSession().canUpload()) {
            throw new ChenException(MessageUtils.get("msg.error.no_permission"));
        }
        try {
            var basePath = SessionManager.getCurrentSession().getTempPath();
            var bytes = file.getBytes();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
            String timestamp = LocalDateTime.now().format(formatter);
            var filename = String.format("sql_%s.sql", timestamp);
            var path = basePath.resolve(filename);
            Files.write(path, bytes);
            return new UploadResponse(filename);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
