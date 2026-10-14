package compliance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ComplianceReportTest {

    @Test
    void renderContainsSummaryAndFindings() {
        List<Finding> findings = List.of(
                Finding.of("MYSQL-002", Severity.HIGH, "secure_file_priv 未限制",
                        "可读写任意路径文件", "限定目录", "secure_file_priv=''"),
                Finding.of("MYSQL-007", Severity.MEDIUM, "二进制日志未开启",
                        "log_bin=OFF", "开启 binlog", "log_bin=OFF")
        );
        String html = ComplianceReport.render("MySQL", "10.0.0.8", "3306", "prod", "root", findings);

        assertTrue(html.contains("数据库安全合规基线报告"));
        assertTrue(html.contains("MYSQL-002"));
        assertTrue(html.contains("secure_file_priv"));
        assertTrue(html.contains("存在高危项"));
        assertTrue(html.contains("badge high"));
    }

    @Test
    void renderEscapesHtml() {
        List<Finding> findings = List.of(
                Finding.of("X-001", Severity.INFO, "<script>alert(1)</script>",
                        "<img src=x>", "&", "evidence<&>"));
        String html = ComplianceReport.render("MySQL", "h", "1", "d", "u", findings);
        assertFalse(html.contains("<script>alert(1)</script>"), "HTML 必须转义");
        assertTrue(html.contains("&lt;script&gt;"));
    }

    @Test
    void verdictLogic() {
        // 安全：无高/中危 -> pass 风格
        String html = ComplianceReport.render("MySQL", "h", "1", "d", "u",
                List.of(Finding.of("I", Severity.INFO, "t", "d", "r", "e")));
        assertTrue(html.contains("未发现明显风险项"));
    }
}
