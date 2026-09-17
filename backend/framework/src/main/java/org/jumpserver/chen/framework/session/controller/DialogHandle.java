package org.jumpserver.chen.framework.session.controller;

public interface DialogHandle extends AutoCloseable {
    DialogHandle NOOP = new DialogHandle() {
        @Override
        public void cancel() {
        }

        @Override
        public void close() {
        }
    };

    void cancel();

    @Override
    void close();
}
