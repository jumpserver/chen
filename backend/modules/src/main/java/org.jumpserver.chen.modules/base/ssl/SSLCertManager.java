package org.jumpserver.chen.modules.base.ssl;

import lombok.Setter;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

public class SSLCertManager {

    @Setter
    private String caCert;          // CA 证书
    @Setter
    private String clientCertKey;   // 客户端私钥 (PEM 格式)
    @Setter
    private String clientCert;      // 客户端证书


    private File caCertFile;
    private File clientCertKeyFile;
    private File clientCertFile;

    // 获取 CA 证书的路径
    public String getCaCertPath() throws IOException {
        if (StringUtils.isEmpty(caCert)) {
            return null;
        }

        if (caCertFile == null) {
            caCertFile = createTempFile("ca-cert", caCert);
        }
        return caCertFile.getAbsolutePath();
    }

    // 获取客户端私钥的路径，并将 PEM 格式的私钥转换为 DER 格式
    public String getClientCertKeyPath() throws Exception {
        if (StringUtils.isEmpty(clientCertKey)) {
            return null;
        }

        if (clientCertKeyFile == null) {
            // 检查 clientCertKey 是否是 PEM 格式并转换为 DER
            clientCertKeyFile = createTempFile("client-cert-key", convertPEMToDER(clientCertKey));
        }
        return clientCertKeyFile.getAbsolutePath();
    }

    // 获取客户端证书的路径
    public String getClientCertPath() throws IOException {

        if (StringUtils.isEmpty(clientCert)) {
            return null;
        }

        if (clientCertFile == null) {
            clientCertFile = createTempFile("client-cert", clientCert);
        }
        return clientCertFile.getAbsolutePath();
    }

    // 销毁资源，如果 autoDestroy 为 true，则删除临时文件
    public void Destroy() {
        deleteTempFile(caCertFile);
        deleteTempFile(clientCertKeyFile);
        deleteTempFile(clientCertFile);
    }

    // 辅助方法：创建临时文件并写入内容
    private File createTempFile(String prefix, byte[] content) throws IOException {
        File tempFile = File.createTempFile(prefix, ".der");
        Files.write(tempFile.toPath(), content);  // 直接写入二进制数据
        tempFile.deleteOnExit(); // JVM 退出时自动删除
        return tempFile;
    }

    // 辅助方法：创建临时文件并写入内容（用于普通字符串内容）
    private File createTempFile(String prefix, String content) throws IOException {
        File tempFile = File.createTempFile(prefix, ".pem");
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write(content);
        }
        tempFile.deleteOnExit(); // JVM 退出时自动删除
        return tempFile;
    }

    // 辅助方法：删除临时文件
    private void deleteTempFile(File file) {
        if (file != null && file.exists()) {
            try {
                Files.delete(file.toPath());
                System.out.println("Deleted file: " + file.getAbsolutePath());
            } catch (IOException e) {
                System.err.println("Failed to delete file: " + file.getAbsolutePath());
            }
        }
    }

    // 将 PEM 格式的私钥转换为 DER 格式
    private byte[] convertPEMToDER(String pemContent) throws Exception {
        // 去掉 PEM 格式的头尾标记，获取 Base64 编码内容
        pemContent = pemContent.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");  // 去掉空格和换行符

        // Base64 解码
        byte[] keyBytes = Base64.getDecoder().decode(pemContent);

        // 使用 PKCS8EncodedKeySpec 来生成 PrivateKey 对象
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");  // 假设是 RSA 私钥
        PrivateKey privateKey = keyFactory.generatePrivate(keySpec);

        // 返回 DER 格式的私钥字节数组
        return privateKey.getEncoded();
    }
}
