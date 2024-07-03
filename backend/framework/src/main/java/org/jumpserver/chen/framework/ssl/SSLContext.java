package org.jumpserver.chen.framework.ssl;

@FunctionalInterface
public interface SSLContext {
    void run(SSLConfig config);
}
