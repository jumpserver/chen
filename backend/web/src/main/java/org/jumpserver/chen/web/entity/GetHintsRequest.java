package org.jumpserver.chen.web.entity;


import lombok.Data;

@Data
public class GetHintsRequest {
    private String nodeKey;
    private String context;
}
