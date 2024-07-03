package org.jumpserver.chen.web.entity;


import lombok.Data;

@Data
public class AuthResponse {
    private String token;
    private String lang;

    public AuthResponse(String token, String lang) {
        this.token = token;
        this.lang = lang;
    }
}
