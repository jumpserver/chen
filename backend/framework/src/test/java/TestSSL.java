import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import java.io.FileInputStream;
import java.io.FileReader;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;

public class TestSSL {

    public static void main(String[] args) throws Exception {
        // 加入 BouncyCastle 作为安全提供者
        java.security.Security.addProvider(new BouncyCastleProvider());

        // 从 PEM 文件中读取 CA 证书
        Certificate caCert = CertificateFactory.getInstance("X.509")
                .generateCertificate(new FileInputStream("/Users/shenchenyang/Desktop/mysql-ssl/cert/ca.pem"));

        // 从 PEM 文件中读取客户端证书
        Certificate clientCert = CertificateFactory.getInstance("X.509")
                .generateCertificate(new FileInputStream("/Users/shenchenyang/Desktop/mysql-ssl/cert/client-cert.pem"));


        // 从 PEM 文件中读取私钥
        PEMParser pemParser = new PEMParser(new FileReader("/Users/shenchenyang/Desktop/mysql-ssl/cert/client-key.pem"));

        Object object = pemParser.readObject();
        JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");

        PrivateKey privateKey;

        if (object instanceof PEMKeyPair) {
            PEMKeyPair pemKeyPair = (PEMKeyPair) object;
            privateKey = converter.getPrivateKey(pemKeyPair.getPrivateKeyInfo());
        } else if (object instanceof PrivateKeyInfo) {
            privateKey = converter.getPrivateKey((PrivateKeyInfo) object);
        } else {
            throw new IllegalArgumentException("Unsupported object type: " + object.getClass().getName());
        }

        // 创建 JKS 密钥库实例
        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, null);

        // 将私钥和证书导入到密钥库
        List<Certificate> certChain = new ArrayList<>();
        certChain.add(clientCert);
        certChain.add(caCert);
        keyStore.setKeyEntry("clientalias", privateKey, "123456".toCharArray(), certChain.toArray(new Certificate[0]));

        // 保存为 JKS 文件
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream("/Users/shenchenyang/Desktop/mysql-ssl/cert/client-keystore.jks")) {
            keyStore.store(fos, "123456".toCharArray());
        }
    }
}