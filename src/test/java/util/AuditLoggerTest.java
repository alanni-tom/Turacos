package util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuditLoggerTest {

    @TempDir
    Path tempDir;
    private String oldHome;

    @BeforeEach
    void setUp() {
        oldHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", oldHome);
    }

    @Test
    void escapeHandlesSpecialChars() {
        assertEquals("", AuditLogger.esc(null));
        assertEquals("a\\\"b", AuditLogger.esc("a\"b"));
        assertEquals("line\\nbreak\\ttab", AuditLogger.esc("line\nbreak\ttab"));
        assertEquals("back\\\\slash", AuditLogger.esc("back\\slash"));
    }

    @Test
    void logWritesJsonLFile() throws Exception {
        AuditLogger.log("EXEC_SQL", "MySQL", "10.0.0.8", "3306", "prod", "root",
                "SELECT 1;", "1\n", 12L);
        Path logFile;
        try (var stream = Files.list(AuditLogger.auditDir())) {
            logFile = stream.findFirst().orElseThrow();
        }
        List<String> lines = Files.readAllLines(logFile);
        assertFalse(lines.isEmpty());
        // 文件可能包含多条记录：断言存在匹配行
        boolean ok = lines.stream().anyMatch(l ->
                l.startsWith("{") && l.endsWith("}")
                        && l.contains("\"action\":\"EXEC_SQL\"")
                        && l.contains("\"dbType\":\"MySQL\"")
                        && l.contains("\"operator\"")
                        && l.contains("\"elapsedMs\":12"));
        assertTrue(ok, "审计日志应包含 EXEC_SQL 记录");
    }

    @Test
    void logDoesNotThrowWhenWritable() {
        assertDoesNotThrow(() -> AuditLogger.logEvent("COMPLIANCE_START", "Redis",
                "127.0.0.1", "6379", "0", "", "note"));
    }
}
