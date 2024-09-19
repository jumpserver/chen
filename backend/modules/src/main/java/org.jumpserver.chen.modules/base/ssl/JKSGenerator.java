package org.jumpserver.chen.modules.base.ssl;

import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;

public class JKSGenerator {

    public final static String JSK_PASS = "jms@123..";

    @Setter
    private String caCert;

    @Setter
    private String clientCert;

    @Setter
    private String clientKey;
    private Path workDir;
    private Path clientJksFilePath;
    private Path caJksFilePath;


    // 适用于仅校验服务器证书的情况
    public JKSGenerator(String caCert) {
        this.caCert = caCert;
    }


    public JKSGenerator() {
    }

    // 适用于开启了ssl 但是不校验服务端证书的情况
    public JKSGenerator(String clientCert, String clientKey) {
        this.clientCert = clientCert;
        this.clientKey = clientKey;
    }

    // 适用于开启了ssl 并且校验服务端证书的情况
    public JKSGenerator(String caCert, String clientCert, String clientKey) {
        this.caCert = caCert;
        this.clientCert = clientCert;
        this.clientKey = clientKey;
    }


    private void initWorkDir() {
        // 创建临时目录
        try {
            this.workDir = Files.createTempDirectory("jks");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void destroy() {
        try {
            Files.deleteIfExists(this.workDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    public boolean fileExists(Path path) {
        return Files.exists(path);
    }

    public Path generateCaJKS() {
        if (StringUtils.isEmpty(this.caCert)) {
            throw new RuntimeException("caCert is empty");
        }


        if (this.workDir == null || !this.fileExists(this.workDir)) {
            this.initWorkDir();
        }

        if (this.caJksFilePath != null && this.fileExists(this.caJksFilePath)) {
            return this.caJksFilePath;
        }

        var caJKSFilePath = this.workDir.resolve("ca.jks");
        try (FileOutputStream fos = new FileOutputStream(caJKSFilePath.toFile())) {
            var caCert = CertificateFactory
                    .getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(this.caCert.getBytes()));

            KeyStore caKeyStore = KeyStore.getInstance("JKS");
            caKeyStore.load(null, null);

            caKeyStore.setCertificateEntry("ca", caCert);
            caKeyStore.store(fos, JSK_PASS.toCharArray());
        } catch (IOException | NoSuchAlgorithmException | CertificateException | KeyStoreException e) {
            throw new RuntimeException(e);
        }
        this.caJksFilePath = caJKSFilePath;
        return caJKSFilePath;
    }

    public Path generateClientJKS() {
        if (StringUtils.isEmpty(this.clientCert) || StringUtils.isEmpty(this.clientKey)) {
            throw new RuntimeException("clientCert or clientKey is empty");
        }

        if (this.workDir == null || !this.fileExists(this.workDir)) {
            this.initWorkDir();
        }
        if (this.clientJksFilePath != null && this.fileExists(this.clientJksFilePath)) {
            return this.clientJksFilePath;
        }


        Security.addProvider(new BouncyCastleProvider());
        var clientJKSFilePath = this.workDir.resolve("client.jks");
        try (FileOutputStream fos = new FileOutputStream(clientJKSFilePath.toFile())) {
            var clientCert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(this.clientCert.getBytes()));

            PEMParser pemParser = new PEMParser(new StringReader(this.clientKey));
            Object object = pemParser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");

            PrivateKey privateKey;

            if (object instanceof PEMKeyPair pemKeyPair) {
                privateKey = converter.getPrivateKey(pemKeyPair.getPrivateKeyInfo());
            } else if (object instanceof PrivateKeyInfo) {
                privateKey = converter.getPrivateKey((PrivateKeyInfo) object);
            } else {
                throw new IllegalArgumentException("Unsupported object type: " + object.getClass().getName());
            }

            KeyStore clientKeyStore = KeyStore.getInstance("JKS");
            clientKeyStore.load(null, null);


            List<Certificate> certChain = new ArrayList<>();
            certChain.add(clientCert);
            clientKeyStore.setKeyEntry("client", privateKey, JSK_PASS.toCharArray(), certChain.toArray(new Certificate[0]));

            clientKeyStore.store(fos, JSK_PASS.toCharArray());
        } catch (IOException | NoSuchAlgorithmException | CertificateException | KeyStoreException e) {
            throw new RuntimeException(e);
        }
        this.clientJksFilePath = clientJKSFilePath;
        return clientJKSFilePath;
    }

}
