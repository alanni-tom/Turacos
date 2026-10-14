package compliance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 基线扫描器容错验证：目标不可达/无权限时必须优雅降级（INFO 记录），不得抛异常。
 */
class BaselineScannerTest {

    @Test
    void scanUnreachableMysqlIsGraceful() {
        List<Finding> findings = BaselineScanner.scan("MySQL", "127.0.0.1", "1", "db", "root", "p");
        assertNotNull(findings);
        // 单个检查失败会生成 INFO 级 errInfo；连接不可达时不应抛异常
        assertTrue(findings.stream().noneMatch(f -> f.severity() == Severity.HIGH));
    }

    @Test
    void scanUnreachableRedisIsGraceful() {
        List<Finding> findings = BaselineScanner.scan("Redis", "127.0.0.1", "1", "0", "", "");
        assertNotNull(findings);
    }

    @Test
    void unknownTypeReturnsEmpty() {
        assertTrue(BaselineScanner.scan("Oracle", "h", "1", "d", "u", "p").isEmpty());
    }
}
