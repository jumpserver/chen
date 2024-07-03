package org.jumpserver.chen.web.entity;


import lombok.Data;

@Data
public class Profile {
    private String dbType;
    private String Username;
    private String AssetName;
    private boolean canCopy = false;
    private boolean canPaste = false;
}
