package org.jumpserver.chen.framework.jms;

import org.jumpserver.chen.framework.jms.entity.CommandRecord;

public interface CommandHandler {
    void recordCommand(CommandRecord commandRecord);

}
