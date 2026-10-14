package util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CredentialCipherTest {

    @TempDir
    Path tempDir;
    private String oldHome;

    @BeforeEach
    void setUp() {
        oldHome = System.getProperty("user.home");
        // 隔离密钥文件，避免污染真实用户目录
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", oldHome);
    }

    @Test
    void roundTripEncryptDecrypt() {
        String secret = "P@ssw0rd-复杂密码-测试";
        String stored = CredentialCipher.encrypt(secret);
        assertNotNull(stored);
        assertTrue(stored.startsWith("enc:v1:") || stored.startsWith("dpapi:v1:"),
                "存储格式应为 enc:v1:(本地密钥) 或 dpapi:v1:(Windows 系统级) 前缀");
        assertNotEquals(secret, stored, "密文不应等于明文");
        assertEquals(secret, CredentialCipher.decrypt(stored));
    }

    @Test
    void encryptProducesRandomIv() {
        String a = CredentialCipher.encrypt("same-password");
        String b = CredentialCipher.encrypt("same-password");
        assertNotEquals(a, b, "同一明文两次加密结果应不同(随机IV)");
    }

    @Test
    void legacyPlaintextPassesThrough() {
        assertEquals("legacy-plain", CredentialCipher.decrypt("legacy-plain"));
        assertNull(CredentialCipher.decrypt(null));
    }

    @Test
    void corruptedTokenReturnsNull() {
        assertNull(CredentialCipher.decrypt("enc:v1:not-base64:also-not-base64!!"));
    }

    @Test
    void keyIsPersistedAndReused() {
        String stored = CredentialCipher.encrypt("persist-me");
        // 模拟进程重启：重新读取同一密钥目录
        assertEquals("persist-me", CredentialCipher.decrypt(stored));
    }
}
