package util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;
import java.util.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 本地凭据加密（AES-256-GCM）。
 *
 * <p>用于避免将数据库密码以明文写入 {@code config.db}。
 * 密钥保存在用户主目录 {@code ~/.turacos/credentials.key}（由本机随机生成），
 * 存储格式为 {@code enc:v1:base64(iv):base64(ciphertext+tag)}。
 *
 * <p>设计目标：以“本地硬盘泄露/误提交”为前提的基线防护，而非密码学意义上的绝对安全
 * （密钥与密文同在用户机器上，等效于 DPAPI/用户级防护）。若部署需要更强保护，
 * 建议后续接入 Windows DPAPI / macOS Keychain / 系统密钥环。
 */
public final class CredentialCipher {

    private static final Logger LOG = Logger.getLogger(CredentialCipher.class.getName());

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final String KEY_ALGO = "AES";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final String PREFIX = "enc:v1:";
    private static final String KEY_FILE = ".turacos/credentials.key";

    private CredentialCipher() {
    }

    /** 加密明文密码，返回可持久化的字符串。入参为 null 时返回 null。
     *  Windows 优先使用 DPAPI（系统级保护），失败回退本地密钥文件 AES 方案。 */
    public static String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        if (Dpapi.available()) {
            try {
                byte[] blob = Dpapi.protect(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return "dpapi:v1:" + Base64.getEncoder().encodeToString(blob);
            } catch (Exception e) {
                LOG.warning("[!] DPAPI 加密失败，回退本地密钥: " + e.getMessage());
            }
        }
        try {
            byte[] key = loadOrCreateKey();
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey(key), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            return PREFIX
                    + Base64.getEncoder().encodeToString(iv) + ":"
                    + Base64.getEncoder().encodeToString(ct);
        } catch (Exception e) {
            // 加密失败时不应把明文静默写盘；回退明文并记录异常，避免数据不可用
            LOG.warning("[!] 密码加密失败，将以明文保存: " + e.getMessage());
            return plain;
        }
    }

    /** 解密存储串；无法解密时返回 null（调用方决定回退策略）。 */
    public static String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        if (stored.startsWith("dpapi:v1:")) {
            try {
                byte[] blob = Base64.getDecoder().decode(stored.substring("dpapi:v1:".length()));
                return new String(Dpapi.unprotect(blob), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                LOG.warning("[!] DPAPI 解密失败（账户/机器变更）: " + e.getMessage());
                return null;
            }
        }
        if (!stored.startsWith(PREFIX)) {
            // 兼容旧版本明文记录
            return stored;
        }
        try {
            String body = stored.substring(PREFIX.length());
            int sep = body.indexOf(':');
            if (sep <= 0) {
                return null;
            }
            byte[] key = loadOrCreateKey();
            byte[] iv = Base64.getDecoder().decode(body.substring(0, sep));
            byte[] ct = Base64.getDecoder().decode(body.substring(sep + 1));

            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, aesKey(key), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warning("[!] 密码解密失败（密钥可能已变更）: " + e.getMessage());
            return null;
        }
    }

    private static SecretKeySpec aesKey(byte[] key) {
        return new SecretKeySpec(key, KEY_ALGO);
    }

    /** 读取或创建本机密钥文件（首次使用时生成 256 位随机密钥）。 */
    private static synchronized byte[] loadOrCreateKey() throws Exception {
        Path keyPath = keyPath();
        if (Files.exists(keyPath)) {
            byte[] data = Files.readAllBytes(keyPath);
            if (data.length == 32) {
                return data;
            }
            LOG.warning("[!] 密钥文件长度异常(" + data.length + ")，将重新生成: " + keyPath);
        }
        if (keyPath.getParent() != null) {
            Files.createDirectories(keyPath.getParent());
        }
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        Files.write(keyPath, key);
        restrictPermissions(keyPath);
        LOG.info("[+] 已生成新的本地加密密钥: " + keyPath);
        return key;
    }

    private static Path keyPath() {
        return Paths.get(System.getProperty("user.home", "."), KEY_FILE).toAbsolutePath();
    }

    /** 尽力收紧密钥文件权限（POSIX 平台仅属主可读写；Windows 尽力而为）。 */
    private static void restrictPermissions(Path path) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | java.io.IOException ignored) {
            // Windows / 非 POSIX 文件系统：忽略
        }
    }

    /** 供测试使用：仅校验格式，不接触真实密钥。 */
    static String prefix() {
        return PREFIX;
    }
}
