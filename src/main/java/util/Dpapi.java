package util;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.PointerByReference;

/**
 * Windows DPAPI（CryptProtectData / CryptUnprotectData）封装。
 *
 * <p>DPAPI 密钥由操作系统按“当前用户 SID”管理，无需自持密钥文件，
 * 密文迁移到其他用户/机器后无法解密——这是桌面工具可用的最强本地保护。
 * 仅 Windows 可用；非 Windows 平台由 {@link CredentialCipher} 回退到本地密钥文件方案。
 */
public final class Dpapi {

    private static final int CRYPTPROTECT_UI_FORBIDDEN = 0x1;

    private Dpapi() {
    }

    /** 是否当前平台支持 DPAPI。 */
    public static boolean available() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private interface Crypt32 extends Library {
        Crypt32 INSTANCE = Native.load("Crypt32", Crypt32.class);

        boolean CryptProtectData(DATA_BLOB pDataIn, String szDataDescr, DATA_BLOB pOptionalEntropy,
                                 Pointer pvReserved, Pointer pPromptStruct, int dwFlags, DATA_BLOB pDataOut);

        boolean CryptUnprotectData(DATA_BLOB pDataIn, PointerByReference ppszDataDescr,
                                   DATA_BLOB pOptionalEntropy, Pointer pvReserved,
                                   Pointer pPromptStruct, int dwFlags, DATA_BLOB pDataOut);
    }

    private interface Kernel32 extends Library {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        Pointer LocalFree(Pointer hMem);
    }

    @Structure.FieldOrder({"cbData", "pbData"})
    public static class DATA_BLOB extends Structure {
        public int cbData;
        public Pointer pbData;

        public DATA_BLOB() {
            super();
        }
    }

    /** 以当前用户身份加密。 */
    public static byte[] protect(byte[] data) throws Exception {
        if (!available()) {
            throw new UnsupportedOperationException("DPAPI 仅支持 Windows");
        }
        Memory inMem = new Memory(Math.max(data.length, 1));
        if (data.length > 0) {
            inMem.write(0, data, 0, data.length);
        }
        DATA_BLOB in = new DATA_BLOB();
        in.cbData = data.length;
        in.pbData = inMem;

        DATA_BLOB out = new DATA_BLOB();
        boolean ok = Crypt32.INSTANCE.CryptProtectData(
                in, "Turacos", null, null, null, CRYPTPROTECT_UI_FORBIDDEN, out);
        if (!ok) {
            throw new IllegalStateException(
                    "CryptProtectData failed, error=" + Native.getLastError());
        }
        try {
            if (out.cbData <= 0 || out.pbData == null) {
                return new byte[0];
            }
            return out.pbData.getByteArray(0, out.cbData);
        } finally {
            Pointer p = out.pbData;
            if (p != null) {
                Kernel32.INSTANCE.LocalFree(p);
            }
        }
    }

    /** 解密（仅当前用户/机器可解）。 */
    public static byte[] unprotect(byte[] blob) throws Exception {
        if (!available()) {
            throw new UnsupportedOperationException("DPAPI 仅支持 Windows");
        }
        Memory inMem = new Memory(Math.max(blob.length, 1));
        if (blob.length > 0) {
            inMem.write(0, blob, 0, blob.length);
        }
        DATA_BLOB in = new DATA_BLOB();
        in.cbData = blob.length;
        in.pbData = inMem;

        DATA_BLOB out = new DATA_BLOB();
        boolean ok = Crypt32.INSTANCE.CryptUnprotectData(
                in, null, null, null, null, CRYPTPROTECT_UI_FORBIDDEN, out);
        if (!ok) {
            throw new IllegalStateException(
                    "CryptUnprotectData failed, error=" + Native.getLastError());
        }
        try {
            if (out.cbData <= 0 || out.pbData == null) {
                return new byte[0];
            }
            return out.pbData.getByteArray(0, out.cbData);
        } finally {
            Pointer p = out.pbData;
            if (p != null) {
                Kernel32.INSTANCE.LocalFree(p);
            }
        }
    }
}
