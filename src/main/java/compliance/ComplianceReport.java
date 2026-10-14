package compliance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 合规检查报告生成器：输出自包含 HTML（无外部依赖），便于内网分发与归档。
 */
public final class ComplianceReport {

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter DISP_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ComplianceReport() {
    }

    /** 生成报告文件（工作目录下 reports/compliance-<时间戳>.html），返回文件路径。 */
    public static Path generate(String dbType, String host, String port,
                                String database, String username, List<Finding> findings)
            throws IOException {
        Path dir = Paths.get("reports");
        Files.createDirectories(dir);
        String ts = LocalDateTime.now().format(FILE_TS);
        Path file = dir.resolve("compliance-" + ts + ".html");
        Files.write(file, render(dbType, host, port, database, username, findings)
                .getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /** 渲染报告 HTML（供 UI 与测试使用）。 */
    public static String render(String dbType, String host, String port,
                                String database, String username, List<Finding> findings) {
        long high = findings.stream().filter(f -> f.severity() == Severity.HIGH).count();
        long med = findings.stream().filter(f -> f.severity() == Severity.MEDIUM).count();
        long low = findings.stream().filter(f -> f.severity() == Severity.LOW).count();
        long info = findings.stream().filter(f -> f.severity() == Severity.INFO).count();

        String verdict;
        String verdictClass;
        if (high > 0) {
            verdict = "存在高危项，建议立即整改";
            verdictClass = "fail";
        } else if (med > 0) {
            verdict = "存在中危项，建议尽快整改";
            verdictClass = "warn";
        } else if (low > 0) {
            verdict = "存在低危项，建议关注";
            verdictClass = "low";
        } else {
            verdict = "未发现明显风险项";
            verdictClass = "pass";
        }

        StringBuilder rows = new StringBuilder();
        if (findings.isEmpty()) {
            rows.append("<tr><td colspan=\"6\" class=\"empty\">未产生检查结果（可能连接失败或无检查项）</td></tr>");
        } else {
            for (Finding f : findings) {
                rows.append("<tr>")
                        .append("<td class=\"id\">").append(esc(f.id())).append("</td>")
                        .append("<td><span class=\"badge ").append(f.severity().cssClass())
                        .append("\">").append(f.severity().label()).append("</span></td>")
                        .append("<td>").append(esc(f.title())).append("</td>")
                        .append("<td class=\"desc\">").append(esc(f.description())).append("</td>")
                        .append("<td class=\"evidence\">").append(esc(f.evidence())).append("</td>")
                        .append("<td class=\"reco\">").append(esc(f.recommendation())).append("</td>")
                        .append("</tr>\n");
            }
        }

        String template = """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                <meta charset="UTF-8">
                <title>数据库安全合规基线报告</title>
                <style>
                  body { font-family: "Microsoft YaHei", "PingFang SC", sans-serif; margin: 0; background: #f5f6fa; color: #2f3542; }
                  .wrap { max-width: 1080px; margin: 24px auto; background: #fff; border-radius: 10px; box-shadow: 0 2px 12px rgba(0,0,0,.08); overflow: hidden; }
                  header { padding: 20px 28px; background: #1e3a5f; color: #fff; }
                  header h1 { margin: 0 0 6px; font-size: 20px; }
                  header .meta { font-size: 12px; color: #cdd9ea; line-height: 1.7; }
                  .summary { display: flex; flex-wrap: wrap; gap: 12px; padding: 18px 28px 6px; }
                  .card { flex: 1; min-width: 120px; border-radius: 8px; padding: 12px 16px; text-align: center; }
                  .card .num { font-size: 26px; font-weight: 700; }
                  .card .lbl { font-size: 12px; color: #57606f; }
                  .card.high { background: #ffe3e3; } .card.high .num { color: #c0392b; }
                  .card.med { background: #fff3cd; } .card.med .num { color: #b7950b; }
                  .card.low { background: #e7f5ff; } .card.low .num { color: #1f6fb2; }
                  .card.info { background: #eef1f4; } .card.info .num { color: #57606f; }
                  .verdict { margin: 12px 28px; padding: 10px 14px; border-radius: 8px; font-weight: 600; }
                  .verdict.pass { background: #d8f5e3; color: #166534; }
                  .verdict.warn { background: #fff3cd; color: #7a5c00; }
                  .verdict.low { background: #e7f5ff; color: #1f5c8b; }
                  .verdict.fail { background: #ffe3e3; color: #a12b1e; }
                  table { width: 100%; border-collapse: collapse; margin: 10px 0 20px; }
                  th, td { padding: 9px 10px; border-bottom: 1px solid #edf0f4; font-size: 13px; vertical-align: top; text-align: left; }
                  th { background: #f0f3f7; font-size: 12px; color: #47515e; white-space: nowrap; }
                  .id { font-family: Consolas, monospace; white-space: nowrap; color: #57606f; }
                  .badge { display: inline-block; padding: 2px 10px; border-radius: 10px; color: #fff; font-size: 12px; white-space: nowrap; }
                  .badge.high { background: #c0392b; } .badge.medium { background: #b7950b; }
                  .badge.low { background: #1f6fb2; } .badge.info { background: #7f8c8d; }
                  .desc, .reco { max-width: 260px; }
                  .evidence { font-family: Consolas, monospace; font-size: 12px; color: #57606f; max-width: 200px; word-break: break-all; }
                  footer { padding: 14px 28px 22px; font-size: 11px; color: #95a5a6; }
                </style>
                </head>
                <body>
                <div class="wrap">
                  <header>
                    <h1>数据库安全合规基线报告</h1>
                    <div class="meta">
                      目标: {{TARGET}} | {{HOST}}:{{PORT}}<br>
                      数据库: {{DB}} &nbsp;|&nbsp; DB用户: {{USER}}<br>
                      生成时间: {{TIME}} &nbsp;|&nbsp; 生成工具: Turacos
                    </div>
                  </header>
                  <div class="summary">
                    <div class="card high"><div class="num">{{HIGH}}</div><div class="lbl">高危</div></div>
                    <div class="card med"><div class="num">{{MED}}</div><div class="lbl">中危</div></div>
                    <div class="card low"><div class="num">{{LOW}}</div><div class="lbl">低危</div></div>
                    <div class="card info"><div class="num">{{INFO}}</div><div class="lbl">信息</div></div>
                  </div>
                  <div class="verdict {{VCLASS}}">结论：{{VERDICT}}</div>
                  <table>
                    <thead><tr><th>ID</th><th>级别</th><th>检查项</th><th>说明</th><th>证据</th><th>整改建议</th></tr></thead>
                    <tbody>
                    {{ROWS}}
                    </tbody>
                  </table>
                  <footer>本报告由 Turacos 合规基线检查生成，仅用于授权范围内的安全评估与整改跟踪。</footer>
                </div>
                </body>
                </html>
                """;

        return template
                .replace("{{TARGET}}", esc(dbType))
                .replace("{{HOST}}", esc(host))
                .replace("{{PORT}}", esc(port))
                .replace("{{DB}}", esc(database))
                .replace("{{USER}}", esc(username))
                .replace("{{TIME}}", LocalDateTime.now().format(DISP_TS))
                .replace("{{HIGH}}", String.valueOf(high))
                .replace("{{MED}}", String.valueOf(med))
                .replace("{{LOW}}", String.valueOf(low))
                .replace("{{INFO}}", String.valueOf(info))
                .replace("{{VCLASS}}", verdictClass)
                .replace("{{VERDICT}}", esc(verdict))
                .replace("{{ROWS}}", rows.toString());
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
