package org.jumpserver.chen.framework.console.state;


import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class State {
    private boolean loading = false;
    private String title;

    public State(String title) {
        this.title = title;
    }

}
