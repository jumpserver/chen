package org.jumpserver.chen.framework.ssl;

public class SSLConfig {
    private boolean useSSL;
    private boolean verifyServerCertificate;
    // ca cert
    private String trustCertificateKeyStoreUrl;
    // client cert
    private String clientCertificateKeyStoreUrl;
    // key
    private String clientKeyStoreUrl;

    public void destroy(){

    }

}
