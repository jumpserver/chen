package org.jumpserver.chen.framework.jms;

import org.jumpserver.chen.framework.jms.exception.ReplayException;

public interface ReplayHandler {

    void init();

    void release() throws ReplayException;

    void writeRow(String row);

    void writeInput(String input);
    void writeOutput(String input);
}
