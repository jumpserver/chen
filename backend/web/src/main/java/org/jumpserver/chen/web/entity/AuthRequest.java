package org.jumpserver.chen.web.entity;

import lombok.Data;

@Data
public class AuthRequest {
    private String token;
    private boolean disableAutoHash = false;
}
