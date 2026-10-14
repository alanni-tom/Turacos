PostgreSQL UDF 资源放置说明
============================

目录约定: src/main/resources/udf/postgresql/<os>/<bits>/<major_version>/

  os   : linux | windows | mac
  bits : 32 | 64
  major_version : 8 ~ 18（主版本号，来自 SELECT substring(current_setting('server_version') FROM '^[0-9]+')）

说明：
  1. 每个版本目录内放置一个 .so（Linux/macOS）或 .dll（Windows）文件，
     文件名随意，工具会取该目录下的第一个普通文件。
  2. 内置资源仅覆盖部分版本（Linux 8-15、Windows 8-9）；
     PG 16/17/18 等新版本请从 https://github.com/sqlmapproject/udfhack 获取
     （或按目标版本自行编译：源码见 sqlmap udfhack 项目 udf/postgresql/）。
  3. 重要：PostgreSQL 的 C 函数 ABI 跨主版本不兼容，
     **不要**将低版本 .so 直接复制到高版本目录使用，可能导致实例崩溃。
  4. 嵌入资源建议保留原始扩展名（.so/.dll），本工具写入目标时统一命名为
     <随机 loid>.so 后再 lo_export，创建函数指向该路径。

校验（可选）：发布前运行 scripts/verify-resources.ps1 生成/校验资源哈希，
CI 也会在构建时校验（防止供应链替换）。
