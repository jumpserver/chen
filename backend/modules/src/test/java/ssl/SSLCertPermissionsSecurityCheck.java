package ssl;

import org.jumpserver.chen.modules.base.ssl.SSLCertManager;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPairGenerator;
import java.util.*;

public class SSLCertPermissionsSecurityCheck {
    public static void main(String[] args) throws Exception {
        var pair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        var manager = new SSLCertManager();
        manager.setClientCertKey("-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----");
        manager.setCaCert("test-certificate");
        try {
            for (String path : List.of(manager.getCaCertPath(), manager.getClientCertKeyPath())) {
                var permissions = Files.getPosixFilePermissions(Path.of(path));
                if (!permissions.equals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))) {
                    throw new AssertionError("credential file is not owner-only: " + permissions);
                }
                if (Files.size(Path.of(path)) == 0) throw new AssertionError("certificate content missing");
            }
            System.out.println("SSL temporary certificate/key owner permissions passed");
        } finally { manager.Destroy(); }
    }
}
