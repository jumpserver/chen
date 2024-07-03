package org.jumpserver.chen.framework.console.entity.response;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class Hints {

    public Hints(Map<String, List<String>> hints) {
        this.hints = hints;
    }

    private Map<String, List<String>> hints;
}
