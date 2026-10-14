<p align="center">
  <img width="96" height="96" alt="favicon" src="https://github.com/user-attachments/assets/1afd412f-75d8-4337-8ecf-afbf485d47fc" />
</p>

<h1 align="center">🦜 Turacos</h1>

<p align="center">
  <b>多数据库安全评估工具 | 后渗透辅助 | 企业级安全测试</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17+-orange?logo=java&logoColor=white" alt="Java Version">
  <img src="https://img.shields.io/badge/Security-Tool-critical?logo=shield-check" alt="Security Tool">
  <img src="https://img.shields.io/badge/license-MIT-blue" alt="License">
  <img src="https://img.shields.io/badge/maintainer-alanni-green" alt="Maintainer">
  <img src="https://img.shields.io/badge/maintainer-HAKUNA%20MATATA-green" alt="Maintainer">
</p>

---

## 📖 项目简介

**Turacos** 是一款专业的多数据库安全评估工具，支持 **PostgreSQL、MySQL、Redis、MSSQL** 等多种数据库的后渗透操作。  
为安全研究人员提供系统化、模块化的数据库安全测试能力，助力企业安全评估与漏洞验证。

---

## ⚠️ 法律声明

<div align="center" style="background-color:#fff3cd; border:1px solid #ffeaa7; border-radius:6px; padding:16px; margin:20px 0;">
<h3>🛡️ 免责声明</h3>
<p><strong>本工具仅供个人安全研究与学习用途。请勿用于非法活动！<br>由于传播、使用本工具而造成的任何后果与损失，均由使用者本人承担。</strong></p>
</div>

---

## 🎯 功能特性

| **功能模块** | **🐬 MySQL** | **🟥 Redis** | **🗄️ MSSQL** | **🐘 PostgreSQL** |
|:-------------|:-----------:|:------------:|:-------------:|:----------------:|
| 命令执行 | ✅ | ✅ | ✅ | ✅ |
| 任意文件读取 | ✅ | 🚫 | ✅ | ✅ |
| 脚本加载 | ✅ | ✅ | ✅ | ✅ |
| 数据浏览 | ✅ | ✅ | ✅ | ✅ |
| 安全基线检查 | ✅ | ✅ | ✅ | ✅ |
| 操作审计留痕 | ✅ | ✅ | ✅ | ✅ |

> **🛡️ 合规能力（V1.4.5+）**
> - **安全基线检查**：主界面右上角「合规基线检查」一键扫描 29 项 CIS/DISA 简化基线（MySQL `secure_file_priv`/空口令账户、Redis `requirepass`/`protected-mode`/ACL、PG `pg_hba`/SSL/审计日志、MSSQL `xp_cmdshell`/`sa`/trustworthy 等），权限不足时如实标注；
> - **报告导出**：生成自包含 HTML 报告（`reports/compliance-<时间戳>.html`）并自动打开；
> - **操作审计留痕**：所有 SQL/命令/模块加载自动写入 `~/.turacos/audit/`（JSON Lines，按天滚动，可导入 SIEM），不含明文口令。

---

## 🚀 快速开始

### 📦 环境要求
- Java **17+**
- 数据库支持：
  - PostgreSQL **9.0+**
  - MySQL **5.7+**
  - Redis **4.0+**
  - MSSQL **2012+**

### 🧩 使用方法
```bash
# 克隆项目
git clone https://github.com/alanni-tom/Turacos.git

# 进入目录并构建（Windows 使用 mvnw.cmd；需要 JDK 17+）
mvnw.cmd clean package        # 打包，产物在 target/Turacos-1.0-SNAPSHOT.jar

# 运行（无需手动加载 JavaFX SDK 模块）
java -jar target/Turacos-1.0-SNAPSHOT.jar

# 开发模式热运行（可选，需安装 javafx-maven-plugin 自动处理模块路径）
mvnw.cmd javafx:run

# 运行单元测试
mvnw.cmd test
```

> ℹ️ 从 V1.4.4 起：
> - fat jar 直接 `java -jar` 即可运行（主类为不继承 `Application` 的启动器 `ui.Start`，规避 JavaFX classpath 启动限制）
> - 本地保存的连接配置密码将使用 AES-256-GCM 加密写入 `config.db`（密钥保存在 `~/.turacos/credentials.key`），旧版明文记录读取后自动升级为密文
> - 内置 SQL Server 驱动，MSSQL 连接开箱即用

> 🛠️ 工程化与可用性（V1.4.5+）：
> - **GUI 重构**：亮色/深色**手动切换**（登录页与主面板均有「深色/浅色」按钮，选择自动记忆，重启生效）；深色为当代审美柔灰蓝（非纯黑）+ 终端绿强调，亮色为浅灰蓝底 + 白面 + 绿色点缀；表格支持横向滚动/列宽拖动/500 行加载
> - **连接池化**：MySQL/PostgreSQL/MSSQL 统一走 `DatabaseAdapter` + HikariCP，消灭重复连接代码
> - **TLS 优先**：MySQL/PG/MSSQL 连接默认先尝试 TLS，服务端不支持时自动回退并在日志提示
> - **Windows DPAPI**：密码在 Windows 上优先由系统 DPAPI 保护（无需自持密钥文件）
> - **供应链校验**：`powershell scripts\verify-resources.ps1` 校验 UDF/Redis 二进制哈希（CI 自动执行）
> - **CI**：`.github/workflows/build.yml` 自动测试+打包（Windows runner）
> - **国际化骨架**：`i18n/messages*.properties` + `I18n`（默认中文/英文可切换）
> - **Redis 键分页**：SCAN 分页浏览（最多 5 页 × 200 键），超大值自动截断

### 🚀 项目预览

<table style="width: 100%; border: none; text-align: center;">
<tr>
    <td style="Turacosidth: 50%; border: none; padding: 10palanni;">
      <img src="C:\Users\alanni\Desktop\Turacos\assets\image-20261014083800431.png" Turacosidth="450" style="maalanni-Turacosidth: 100%; height: auto;"><br>
      <b>登录窗口</b>
    </td>
    <td style="Turacosidth: 50%; border: none; padding: 10palanni;">
      <img src="C:\Users\alanni\Desktop\Turacos\assets\image-20261014083830239.png" Turacosidth="450" style="maalanni-Turacosidth: 100%; height: auto;"><br>
      <b>数据浏览</b>
    </td>
  </tr>
  <tr>
    <td style="Turacosidth: 50%; border: none; padding: 10palanni;">
      <img src="C:\Users\alanni\Desktop\Turacos\assets\image-20261014084858230.png" Turacosidth="450" style="maalanni-Turacosidth: 100%; height: auto;"><br>
      <b>功能模块</b>
    </td>
    <td style="Turacosidth: 50%; border: none; padding: 10palanni;">
      <img src="C:\Users\alanni\Desktop\Turacos\assets\image-20261014085616669.png" Turacosidth="450" style="maalanni-Turacosidth: 100%; height: auto;"><br>
      <b>导出报告</b>
    </td>
  </tr>
</table>


👥 开发团队
🏅 核心贡献者

<table> <tr> <td align="center"> <a href="https://github.com/alanni-tom"> <img src="https://avatars.githubusercontent.com/alanni-tom" width="100px;" alt="alanni"/> <br/><sub><b>alanni</b></sub> </a><br/> <span>项目开发</span> </td> <td align="center"> <a href="https://github.com/A-HakunaMatata"> <img src="https://avatars.githubusercontent.com/A-HakunaMatata" width="100px;" alt="HAKUNA MATATA"/> <br/><sub><b>HAKUNA MATATA</b></sub> </a><br/> <span>UI 设计</span> </td> </tr> </table>

🙏 特别鸣谢

感谢 HAKUNA MATATA 提供的出色 UI 设计，使工具的视觉体验更具现代感与专业性。

📄 许可证

本项目采用 MIT License 开源协议
详细信息请参阅 LICENSE

<p align="center"> 🔐 <b>安全研究 · 专业工具 · 持续更新</b> <br/> 💬 欢迎提交 Issue 或 PR，一起完善 Turacos！ </p>
