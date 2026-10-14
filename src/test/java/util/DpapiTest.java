package util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Windows DPAPI 往返测试：仅在本平台为 Windows 时执行（CI 非 Windows 自动跳过）。
 */
class DpapiTest {

    @Test
    void dpapiRoundTripOnWindows() {
        org.junit.jupiter.api.Assumptions.assumeTrue(Dpapi.available(), "仅 Windows 支持 DPAPI");
        try {
            byte[] data = "window-dpapi-secret-机密".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] blob = Dpapi.protect(data);
            assertFalse(java.util.Arrays.equals(data, blob), "DPAPI 密文不应等于明文");
            assertArrayEquals(data, Dpapi.unprotect(blob), "DPAPI 应能正确解密");
        } catch (Exception e) {
            fail("DPAPI 调用异常: " + e.getMessage(), e);
        }
    }
}
