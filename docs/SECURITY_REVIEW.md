# Turacos 安全审计与工程优化报告

- **审计对象**：Turacos V1.4.3（JavaFX + JDBC 的多数据库安全评估工具）
- **审计日期**：2026-10-13
- **审计视角**：高级红队工程师（工具自身可靠性 / 工具自身安全 / 工程质量三线并审）
- **范围**：`src/main/java` 全部 13 个类、`pom.xml`、构建产物、部署文档
- **结论**：核心功能架构合理（模块化 + 适配器思想雏形），但存在 **1 个致命功能缺陷、3 个高危缺陷、5 个中危缺陷**；本次已修复全部高/中危问题并补测试，剩余优化见路线图。

---

## 一、总体发现汇总

| # | 严重性 | 问题 | 状态 |
|---|--------|------|------|
| F1 | 🔴 致命 | MSSQL 功能不可用（`pom.xml` 缺失 `mssql-jdbc` 驱动，`jdbc:sqlserver://` 必然报 No suitable driver） | ✅ 已修复 |
| F2 | 🔴 致命 | `java -jar` 无法以 fat jar 启动（shade 主类为 `ui.Main`，JavaFX 模块化 jar 在 classpath 模式下要求主类不继承 `Application`） | ✅ 已修复 |
| F3 | 🟠 高危 | 数据库密码明文存储于 `config.db`，且未加入 `.gitignore` | ✅ 已修复 |
| F4 | 🟠 高危 | SQL 执行按 `;` 盲拆，破坏函数体/字符串内分号/注释/`COPY FROM PROGRAM`，导致 UDF 与命令执行类语句误拆失败 | ✅ 已修复 |
| F5 | 🟠 高危 | `controllers.loadDatabases()` 先 `close()` 再 `getDatabases()`（隐性依赖内部新建连接才可用） | ✅ 已修复 |
| F6 | 🟡 中危 | Redis `getConfigValue()` 使用 Jedis 5.x 之前的 `List<String>` 强转；Jedis 4+ 返回 `Map`，`ClassCastException` 被吞掉后配置读取永远返回 null（复制投送回退路径失效） | ✅ 已修复 |
| F7 | 🟡 中危 | Redis 复制投送：`RogueMaster` 绑定 *0.0.0.0*（局域网任意主机可 PSYNC）；异常路径无 `finally` 清理，可能把目标 Redis 遗留在“指向本地端口的从库”状态 | ✅ 已修复 |
| F8 | 🟡 中危 | 建立连接即向目标写入并 `MODULE LOAD` 二进制模块（无用户显式触发，侵入性行为不可预期） | ✅ 已修复（改为按钮显式触发，并已接入 FXML） |
| F9 | 🟡 中危 | UI 线程阻塞：双击加载表结构/表数据、`btnExecuteSql`、Redis 模块加载均在 FX 线程同步执行 DB 查询 | ✅ 已修复 |
| F10 | 🟡 中危 | 裸 `new Thread(...)` 散布多处（无线程名、非 daemon、无统一生命周期）；`platform` 线程不受控 | ✅ 已修复 |
| F11 | 🟢 低危 | 表名/列名等标识符直接拼接进 SQL（`SELECT * FROM <table>`），无方言引用 | ✅ 已修复 |
| F12 | 🟢 低危 | `e.printStackTrace()` / `System.out` 遍布错误路径，无日志规范 | ✅ 已修复（核心类改用 JUL） |
| F13 | 🟢 低危 | `panel.initDatabases()` 同步版为死代码（95 行），与异步版逻辑漂移 | ✅ 已删除 |
| F14 | 🟢 低危 | `redis.moduleLoad()` 重复完全相同的 try 块 ×2；`autoLoadModuleFromResources` 残留 `System.out.println(osKey/bits)` 调试输出 | ✅ 已修复 |
| F15 | 🟢 低危 | `maven-resources-plugin` 默认 2.6 在 Windows/杀软环境下复制 `.dll/.so` 失败 | ✅ 已修复（3.3.1） |
| F16 | 🟢 低危 | 依赖整体滞后；缺少 LICENSE 文件（README 声称 MIT）；`main.css` 使用 JavaFX 不支持的 `-fx-transform: scale()`/`-fx-transition`（启动时 CSS 解析报错） | ✅ 已修复 |
| F17 | 📋 建议 | 无任何测试与 CI；Redis `database`（逻辑库索引）参数被忽略；PG UDF 资源仅覆盖到 v15 | ✅ 测试已补；Redis database 已修复；UDF 资源见路线图 |
| F18 | 📋 建议 | 无审计留痕（谁、何时、对哪个库执行了什么语句）——对“合规工具”定位是硬伤 | ✅ 已落地（P0） |

---

## 二、关键修复详解

### F2/F12：fat jar 启动与 JavaFX 模块化
- 现象：`java -jar Turacos-1.0-SNAPSHOT.jar` 直接报 *“JavaFX runtime components are missing”*，README 只能要求用户手动“加载 JavaFX SDK 模块”。
- 根因：JavaFX 21 为模块化 jar；当启动类的 `main` 位于继承 `javafx.application.Application` 的类时，classpath 启动被 JDK 拒绝。
- 修复：shade 主类改为 `ui.Start`（仅调用 `Main.main` 的非 Application 启动器，项目本来就有，但从未被使用）+ shade 过滤 `module-info.class`；并接入 `javafx-maven-plugin`（`mvn javafx:run`）。
- 验证：冒烟启动 10 秒进程存活，无启动异常；仅余两条无害 CSS 警告（见 F16，已修复）。

### F3：凭据落地加密
- 新增 `util/CredentialCipher`：AES-256-GCM，随机 IV，格式 `enc:v1:<iv>:<ct>`；密钥保存在 `~/.turacos/credentials.key`（POSIX 上设为仅属主 600）。
- `DatabaseConfigDAO` 读写统一走加解密；旧版明文记录解密时直通（无前缀），下次保存自动升级为密文；密钥丢失/损坏时告警并保留原值，避免静默丢密。
- `.gitignore` 补充 `config.db`、`config.db-*`、`.turacos/`。
- 说明：密钥与密文同机，属于“用户级防护”等价的基线方案；若要求更高，路线图中建议接入 Windows DPAPI。

### F4：多语句执行由“盲拆 `;`”改为“整串发送 + 逐结果集迭代”
- MySQL `allowMultiQueries=true`、PostgreSQL simple query 协议、MSSQL `getMoreResults()` 均原生支持多语句；此前按 `;` 拆分：
  - PostgreSQL：`CREATE FUNCTION ... AS $$ ... ; ... $$` 被拆断 → UDF 加载必然失败；
  - `COPY ... FROM PROGRAM '...;...'`、字符串内分号被误拆；
  - MySQL：存储过程体/注释内分号被误拆。
- 现改为：整串执行 → `while(getMoreResults)` 迭代所有结果集，错误统一收集，超时统一处理（PG 对 `COPY PROGRAM` 保持 5 秒超时）。

### F6：Jedis 版本适配
- `configGet` 在 Jedis 4+ 返回 `Map<String,String>`（旧版为 `List`），原强转在任何版本都会抛 `ClassCastException` 且被吞 → 回退读取 dbfilename/dir 永远失败。
- 修复后按 `Map` / `List` 双形态防御解析，并保留 `configGet("*")` 兜底。

### F7：Redis 复制投送的 OPSEC 与健壮性
- `RogueMaster` 改为 `bind(本机IPv4, 0)`，不再监听 `0.0.0.0`；
- `pushModuleViaReplication` 增加 `finally`：无论成败都 `SLAVEOF NO ONE` + 关闭本地服务，避免目标库残留从库指向；
- 构造函数补齐 `database` 参数（逻辑库索引，此前整个被忽略）；`moduleLoad` 去重；`reverseShell` 按目标 OS 选择命令顺序（Windows 不再先试 bash 浪费 3 轮）。

### F8：模块加载的“无感入侵”收敛
- 连接建立时不再自动 `MODULE LOAD`；改为 Redis 功能页“加载模块”按钮显式触发（已在 `panel.fxml` 接入 `#loadRedisModuleButton`），并在日志中提示该操作具有侵入性。

### F9/F10：线程模型
- 新增守护线程池 `WORKER`（命名 `turacos-worker`，daemon，cached pool）替换全部 `new Thread`；
- 双击加载结构/表数据、SQL 执行、模块加载全部后台化；TreeView/TableView 的变更统一在 `Platform.runLater` 内完成（避免 `ObservableList` 跨线程修改异常）。

### F11：标识符引用
- 新增 `util/SqlHelpers`：`quotePg/quoteMy/quoteMs` 分别按方言转义；`getTopRows` 行数参数化 + `clampLimit(1..5000)` 防误拉全表；host/port 前置校验收敛到一处。

### F16：构建与资产
- 依赖升级：JavaFX 21.0.12（LTS 安全线）、postgresql 42.7.14、mysql-connector-j 9.7.0、jedis 8.0.2、sqlite-jdbc 3.53.4.0、mssql-jdbc 13.6.0.jre11；
- 新增 `mssql-jdbc`（F1）、`slf4j-nop`（消除 MSSQL 驱动 SLF4J 绑定警告）；
- 补 `LICENSE`（MIT）；`main.css` 兼容 JavaFX CSS（`scale()` → `-fx-scale-x/-fx-scale-y`，移除 `transition`）。

---

## 三、P0 落地：操作审计留痕 + 安全基线检查 + 报告导出

针对路线图 P0（本工具“合规”定位的立身之本）已完成落地，新增模块：

### 1. 操作审计留痕（`util/AuditLogger`）
- 所有敏感操作自动记录为 **JSON Lines**，按天滚动写入 `~/.turacos/audit/audit-YYYY-MM-DD.log`，可直接导入 SIEM/ELK；
- 覆盖路径：四库 `executeSQL`（EXEC_SQL）、MySQL 命令执行/反弹（EXEC_CMD/REVERSE_SHELL）、Redis 命令/读文件/模块加载/`MODULE_LOAD`（EXEC_CMD/READ_FILE/MODULE_LOAD/MODULE_AUTO_LOAD）、PG/MSSQL 反弹、合规检查本身（COMPLIANCE_START/DONE）；
- 字段：`ts / operator / action / dbType / host / port / database / dbUser / detail(SQL或命令) / result(结果摘要) / elapsedMs`；
- 原则：**绝不含明文口令**；SQL 内容截断 2000 字符、结果截断 300 字符；写入失败仅记录不中断业务。

### 2. 安全基线检查（`compliance/BaselineScanner`）
按 CIS/DISA 简化集实现，共 **29 个检查项**，逐库执行且单项失败不中断：

| 数据库 | ID 范围 | 覆盖 |
|--------|---------|------|
| MySQL | MYSQL-001~008 | 版本 EOL、`secure_file_priv`、`local_infile`、`skip_grant_tables`、空口令账户、`Host='%'` 远程账户、`log_bin`、认证插件 |
| PostgreSQL | PG-001~008 | 版本 EOL、额外超级用户、无口令登录角色、`pg_hba` trust、`log_connections`/`log_statement`、SSL、`listen_addresses` |
| MSSQL | MSSQL-001~009 | 版本 EOL、`xp_cmdshell`、OLE Automation、CLR、Ad Hoc Distributed Queries、登录审计、`sa` 账户与口令策略、trustworthy 库 |
| Redis | REDIS-001~006 | 版本、`requirepass`、`protected-mode`、`bind`、`rename-command`、ACL 默认用户 `nopass` |

- 权限不足/版本不支持时如实记录为“信息”级，不误报为通过；
- 检查过程同样写入审计日志（COMPLIANCE_START/DONE）。

### 3. 报告导出（`compliance/ComplianceReport`）
- 自包含 HTML（无外部依赖），样式隔离，适合内网归档；
- 内容：目标信息、高/中/低/信息四色卡片、结论（按最高级别给出整改意见）、逐项表格（ID/级别/说明/证据/整改建议，全部 HTML 转义）；
- 输出位置：工作目录 `reports/compliance-<yyyyMMdd-HHmmss>.html`，生成后自动用系统浏览器打开。

### 4. UI
- 主界面右上角新增「合规基线检查」按钮（`panel.fxml` + `panel#runComplianceCheck`），后台执行不卡 UI；
- 执行结果摘要打在日志区，报告路径在按钮旁提示。

> 结构化查询基础设施：各适配器新增 `queryRows(sql, params...)`（列名→值），Redis 新增 `configGetValue/serverInfo/aclList`，后续扩展检查项无需触碰 UI 层。

---

## 四、验证记录

```text
mvnw.cmd test           → Tests run: 20, Failures: 0, Errors: 0
                          （CredentialCipher 5 / SqlHelpers 5 / AuditLogger 3 / ComplianceReport 3 /
                           BaselineScanner 3 / Dpapi 1[Windows 实跑 DPAPI 加解密往返]）
powershell scripts\verify-resources.ps1
                        → [PASSED] 28 entries；-fix 重新生成清单验证通过
mvnw.cmd package        → fat jar 34MB，Main-Class: ui.Start
                          （HikariCP / JNA / compliance / DatabaseAdapter / i18n 资源均已打入）
jar 冒烟（8s）           → 进程存活，无 JavaFX 致命错误
服务文件合并             → META-INF/services/java.sql.Driver 同时注册
                          postgresql / mysql / sqlite / sqlserver 四驱动（ServicesResourceTransformer）
```

---

## 五、路线图（更新至 2026-10-13：P0 / P1 / P2 全部落地 ✅）

### P0 - 合规能力 ✅ 已落地
1. **操作审计留痕**：落地为 `util/AuditLogger`（JSON Lines，按天滚动）；
2. **安全基线检查模块**：落地为 `compliance/BaselineScanner`（29 项检查，MySQL/PostgreSQL/Redis/MSSQL）；
3. **报告导出**：落地为 `compliance/ComplianceReport`（自包含 HTML，自动打开）。

### P1 - 工程化 ✅ 已落地
4. **连接池化 + 适配器统一**：新增 `DatabaseAdapter` 接口 + `DatabaseFactory` 工厂 + HikariCP(4.0.3) 连接池；`mysql/psql/mssql` 实现统一接口，`panel`/`controllers` 中加载结构/表数据/SQL 执行的重复分支合并为单一路径；Redis 保留独立类（能力模型不同）；
5. **CI + 供应链校验**：`.github/workflows/build.yml`（Windows runner：资源哈希校验 → `mvnw test` → `package` → 上传 artifact）；`scripts/verify-resources.ps1` + `src/main/resources/RESOURCE_SHA256.txt`（28 个二进制资源 SHA-256 清单，`-fix` 可重建）；
6. **UDF/CLR 资源**：PG 16/17/18 等新版**不内置伪造二进制**（PG C 函数 ABI 跨主版本不兼容，用错版本会导致实例崩溃）——改为：资源缺失时日志显式提示来源（sqlmapproject/udfhack）+ `udf/postgresql/README.txt` 放置规范；MSSQL CLR 提供参考源码 `src/main/resources/mssql/clr/ClrExec.cs`（自行 `csc /target:library` 编译）并更新 CLR 分支提示；
7. **TLS 分级**：MySQL（`useSSL=true` 尝试 → 回退明文）、PostgreSQL（`sslmode=require` → `prefer`）、MSSQL（`encrypt=true` → `false`）均在首次连接探测一次，失败回退并记录警告；
8. **系统级密钥**：Windows 优先 **DPAPI**（`CryptProtectData`，JNA 封装 `util/Dpapi`，存储格式 `dpapi:v1:`，密钥由用户 SID 管理）；非 Windows 自动回退本地密钥文件 AES（`enc:v1:`）。

### P2 - 可用性 ✅ 已落地
9. **清理与合并**：删除未使用控件 `psqlOutputArea`；`init.java` 两个 `loadPanel` 重载合并为单一入口（统一标题/尺寸/不可调整）；
10. **国际化骨架**：`util/I18n` + `i18n/messages.properties`（中文默认）/ `messages_en.properties`，新增功能文案走资源（如合规检查提示），存量文案可后续逐步迁移；
11. **Redis 键分页与截断**：`redis.scanKeys(cursor, count)`（SCAN 分页，替代 `KEYS *` 风险）；树加载最多 5 页（约 1000 键）；`getKeyPreview` 单值截断 4096 字符、集合条目上限 200，防止超大值拖垮 UI。

### GUI 重构（V1.4.5）✅ 已落地
- **亮/暗主题手动切换**：登录页与主面板均有「深色/浅色」切换按钮（`ui.ThemeManager`），选择持久化（Preferences）重启生效；实现为 “主样式=基础+亮色（main.css/panel.css），深色由 theme-dark.css 覆盖层追加到根节点样式表”；
- **深色配色（当代审美）**：柔灰蓝 `#20242e` 系（表面逐级 #262c38/#2b3140，非死黑）+ 终端绿 `#4ade80` 强调；亮色为浅灰蓝底 + 白面 + 绿色点缀；两套主题共用同一布局选择器；
- **登录页**：品牌侧栏+表单卡片（740×470），绿色顶边线、等宽大写 TURACOS、聚焦绿光输入框、绿实心/绿描边按钮；
- **主面板**：顶部导航栏（Logo + 合规基线检查 + 主题切换）；数据库树/功能区/Tab/表格/日志整套双主题皮肤；MySQL 树扁平化（库→表）、单击数据库节点即展开表；
- **表格**：非约束列宽（横向滚动/拖列宽）、行高 26px、悬停高亮、500 行加载、空态占位；
- **修复**：JavaFX CSS `linear-gradient(<deg>)` 不识别（改方向关键字）；`Main` 主类通用 `Parent` 加载；Tab 默认关闭按钮（tabClosingPolicy=UNAVAILABLE）；emoji 渲染方框（改图标/纯文本）。

---

## 六、约束与免责

本报告仅针对**工具自身**的代码质量、可靠性、自安全与工程化进行审计与优化；工具能力边界、使用场景及合规性由使用者自行负责，本项目据此遵守其 LICENSE（MIT）与 README 中法律声明。
