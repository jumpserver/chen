package org.jumpserver.chen.framework.ssl;

import java.util.Properties;

public class SSlUtils {
    public static void withSSLProps(Properties props, SSLContext context) {
        SSLConfig config = new SSLConfig();
        context.run(config);
        config.destroy();
    }

}
