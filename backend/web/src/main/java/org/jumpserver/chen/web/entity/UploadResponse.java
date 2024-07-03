package org.jumpserver.chen.web.entity;


import lombok.Data;

@Data
public class UploadResponse {
    private String path;

    public UploadResponse(String path) {
        this.path = path;
    }
}
