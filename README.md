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

# 进入目录并编译
cd turacos
mvn clean package

# 运行工具, 这里需要加载 javafx sdk模块
java -jar Turacos-1.0-SNAPSHOT.jar
```

### 🚀 项目预览

<img width="899" height="629" alt="image" src="https://github.com/user-attachments/assets/f12deb0f-d31f-413b-9bd6-b120fa6ffa8e" />

<img width="903" height="634" alt="image" src="https://github.com/user-attachments/assets/bab4434c-5908-40ae-8af9-780675cc95cd" />



👥 开发团队
🏅 核心贡献者

<table> <tr> <td align="center"> <a href="https://github.com/alanni-tom"> <img src="https://avatars.githubusercontent.com/alanni-tom" width="100px;" alt="alanni"/> <br/><sub><b>alanni</b></sub> </a><br/> <span>项目开发</span> </td> <td align="center"> <a href="https://github.com/A-HakunaMatata"> <img src="https://avatars.githubusercontent.com/A-HakunaMatata" width="100px;" alt="HAKUNA MATATA"/> <br/><sub><b>HAKUNA MATATA</b></sub> </a><br/> <span>UI 设计</span> </td> </tr> </table>

🙏 特别鸣谢

感谢 HAKUNA MATATA 提供的出色 UI 设计，使工具的视觉体验更具现代感与专业性。

📄 许可证

本项目采用 MIT License 开源协议
详细信息请参阅 LICENSE

<p align="center"> 🔐 <b>安全研究 · 专业工具 · 持续更新</b> <br/> 💬 欢迎提交 Issue 或 PR，一起完善 Turacos！ </p>
