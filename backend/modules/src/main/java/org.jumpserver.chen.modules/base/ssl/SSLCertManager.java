package org.jumpserver.chen.modules.base.ssl;

import lombok.Setter;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;

public class SSLCertManager {

    @Setter
    private String caCert;          // CA 证书
    @Setter
    private String clientCertKey;   // 客户端私钥
    @Setter
    private String clientCert;      // 客户端证书


    private File caCertFile;
    private File clientCertKeyFile;
    private File clientCertFile;

    // 获取 CA 证书的路径
    private String getCaCertPath() throws IOException {
        if (caCertFile == null) {
            caCertFile = createTempFile("ca-cert", caCert);
        }
        return caCertFile.getAbsolutePath();
    }

    // 获取客户端私钥的路径
    private String getClientCertKeyPath() throws IOException {
        if (clientCertKeyFile == null) {
            clientCertKeyFile = createTempFile("client-cert-key", clientCertKey);
        }
        return clientCertKeyFile.getAbsolutePath();
    }

    // 获取客户端证书的路径
    private String getClientCertPath() throws IOException {
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
}
