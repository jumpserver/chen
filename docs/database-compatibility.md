# Go 数据库工作区：兼容性与验收

2026-10-26：旧版 Chen 的九类数据库入口已接入 Go 后端与 Luna Monaco，并完成本轮真实实例回归。
本页前期记录覆盖只读工作区；随后已加入与 Java dev 对齐的网格写入、CSV 导入和结构修改，当前功能及限制见[写入说明](writes.md)。下列只读历史记录不代表写入测试。

## dev 迁移后的增量验收

在更新到本机最新 `dev` 并迁移 Go/Luna 后，10 个连接（本地隔离 PostgreSQL 加下表九个远程实例）的读取矩阵再次通过；目录详情另验证 Oracle/达梦原生 DDL 与 PostgreSQL 列权限过滤。完整 Luna 通过 Core 的真实登录、资产树及 Go 工作区入口；托管策略与录像验收见 [Go V1](go-v1.md)。

CSV/XLSX 完整导出不使用普通查询的 10,000 行/16 MiB 缓存窗口。真实 PostgreSQL 分别完成 12,000 行、约 20.6 MB 数据导出，并对默认 1,000,000 行和 128 MiB 上限实测明确失败、禁止部分文件下载及原连接恢复。格式约束和下载策略见 [导出说明](exports.md)。

以下时间、版本与浏览器细节保留为前一轮兼容性证据；不能将单机验证等同于每种数据库的全部托管功能或每个镜像架构都已通过。

## 真实实例结果

下列九项已使用用户提供的测试实例，通过 `TestReadonlyDatabaseMatrix`。
测试只读取目录元数据，使用 `WHERE 1=0` 校验带引号对象引用，通过纯常量 SELECT 生成大字段。
未创建、修改或删除数据库对象，未读取业务表记录，未使用 Keycloak 专用数据库。

| 数据库 | 连接与目录 | 表名/字段/别名/JOIN | 大字段、精确值及审计 | 限制 |
| --- | --- | --- | --- | --- |
| MySQL | 通过 | 通过 | 通过 | TiDB 语法与 MySQL 并非完全相同 |
| MariaDB | 通过 | 通过 | 通过 | 扩展 SELECT 语法可交服务器验证 |
| PostgreSQL | 通过 | 通过 | 通过 | 本地语法版本为 PostgreSQL 17 |
| SQL Server | 通过 | 通过 | 通过 | 支持 `[]` 引用和 TOP 投影补全 |
| Oracle | 通过 | 通过 | 通过 | 目录支持 Oracle 12c+；database 为 service name |
| Dameng | 通过 | 通过 | 通过 | 驱动不能可靠停止运行中的查询，主动取消已禁用；TLS 厂商证书配置尚未接入 |
| Db2 | 通过 | 通过 | 通过 | 需 IBM CLI；暂不支持主动取消已运行查询；TLS 配置尚未接入 |
| ClickHouse | 通过 | 通过 | 通过 | 支持 native/HTTP；数组内 UInt64 保留为精确字符串 |
| OceanBase MySQL 租户 | 通过 | 通过 | 通过 | 使用官方 OceanBase Go 驱动 |
| OceanBase Oracle 租户 | 已实现，未实测 | 已实现及单测 | 未实测 | 提供的 CE 环境无 Oracle 租户，不以 MySQL 测试替代 |

共同验收包括：身份对应的对象树、字段类型、schema 限定表名、别名字段、JOIN 同名字段限定、零行 SELECT、约 150–350 KiB 长文本（含中文/emoji）、超过 JavaScript 安全整数范围的数值、JSON 文本、NULL/空串、二进制十六进制（ClickHouse 测数组），以及真实 standalone 审计落盘。
Oracle 将空字符串视为 NULL，工作区保留数据库本身语义。

Db2 另通过 DBCLOB 中文/emoji、ASCII BLOB、精确 DECIMAL、CLI 原生超时及严格 CGO 指针检查回归。CLI `QueryTimeout=1` 的计数查询约 1.3 秒返回 SQLSTATE 57014，随后连接可复用；服务默认 CLI QueryTimeout 为 60 秒。主动取消能力仍明确为 false，不能把超时等同于随时可取消。

本轮九库矩阵38.44秒通过，矩阵与取消组合测试55.23秒通过；Go带IBM CLI的全包race及常规vet通过。Luna 95项单元测试、类型检查、lint和静态构建通过。Monaco补全、大字段和审批/暂停交互另有浏览器fixture覆盖；该fixture使用模拟HTTP/SSE，不替代真实Core/Wisp验证。

取消实测分开记录：

| 数据库 / 路径 | 本轮证据 |
| --- | --- |
| MySQL、MariaDB、PostgreSQL、ClickHouse native、OceanBase MySQL | 真实HTTP取消，确认服务器查询终止、审计记录、原物理连接可继续查询 |
| SQL Server | 驱动层WAITFOR取消55ms并复用原连接；直接QueryContext/ExecContext的attention确认可判定取消，Rows.Err的context错误不足以确认 |
| ClickHouse超时、会话关闭 | 精确query_id从服务器消失且审计落盘；原请求失去响应，终态保守为unknown |
| Dameng | 取消后等待当前驱动调用安全返回再丢弃连接，运行中主动取消能力关闭；超时和管理员关闭保持 unknown，不假定服务器写入被回滚 |
| Db2 | 主动取消不支持；既有CLI原生超时独立验证 |
| Oracle | 测试账号无DBMS_LOCK.SLEEP权限，未验证运行中取消；没有用高负载业务查询代替 |

Oracle与Dameng新增原生空BLOB/CLOB、NULL LOB、非空二进制、中文emoji NCLOB回归。Db2另通过3MB Unicode DBCLOB、空值和精确DECIMAL回归。

浏览器额外复核 Oracle 对象树/别名/JOIN/CLOB 分段，SQL Server 方括号补全接受后执行零行查询，Db2 大文本与尾部查找；本轮更新后的本地PG页面再次验收表名与JOIN别名字段补全、取消后原连接可用、475 KiB长文本第二片段尾部查找、JSON大整数精度和二进制Hex/ASCII；页面保留纯常量JOIN样例。九库通用路径由 HTTP 实库 matrix 覆盖，未声称对九库全部 UI 功能分别穷举。

## 本轮实测修复

- ClickHouse为每次执行分配服务器生成的query_id，以独立控制连接精确KILL，修复客户端取消但服务器继续运行；自动超时和管理关闭也使用该路径。
- Oracle改为`LOB FETCH=post`并修复go-ora空LOB转换，区别空BLOB/CLOB与NULL。固定版本源代码和许可证位于`third_party/go-ora`，仅一处转换逻辑补丁，说明见`CHEN_PATCH.md`。
- SQL Server 连接配置使用驱动完整默认值，修复初始化 panic。
- Dameng 按其实际 DSN 解析规则处理密码，修复错误的 URL 编码。
- Dameng 的取消 watcher 会与语句/事务状态并发修改，已在统一 connector 边界屏蔽驱动内部异步关闭，覆盖目录、预编译、事务、Ping 与结果集。调用前检查取消，调用中取消后等待安全返回并丢弃连接；这不是服务器主动取消。固定 dial 10 秒、每次读取 120 秒、每次写入 15 秒，不能把每次 I/O 上限当成整个查询的硬上限。驱动自身 `socketTimeout` 在该版本仅影响默认 TCP 拨号，因此由独立 socket 包装实际设置 deadline；通讯失败按结果未知处理，不自动重试 SQL。
- Dameng 固定版本源码另修复全局错误模板、过滤器配置读取及 DH 固定参数初始化的跨连接竞态，参见 `third_party/dm/CHEN_PATCH.md`。真实实例回归覆盖三连接并发读取超时、预编译 deadline、取消后的结果集清理，以及持有行锁时的超时和管理员会话关闭；后两者验证服务器最终仍可完成写入，而 API 与持久审计保持 unknown。
- Oracle 改用有针对性的目录查询，保持 3 秒目录超时，并隐藏 Oracle 维护的系统模式。
- IBM Go 驱动的 CGO 缓冲、空字符串、DBCLOB Unicode、精确小数读取已做固定版本源代码补丁；保留许可证和上游 SHA，详见 `third_party/go_ibm_db/CHEN_PATCH.md`。未关闭 CGO 检查，也未分发 IBM CLI 二进制到源码仓库。
- 补全修复 SQL Server TOP CTE、Oracle MINUS、NQ 与多字节 q 引号边界；引用名称保持正确的大小写与转义。
- 大字段查看器保留分段、完整复制、JSON 精确格式化、二进制 Hex/ASCII；查找不再自动把整个长单词填入输入框。

## 验证入口

```sh
# 常规构建不包含需要本机 IBM CLI 的 Db2 驱动。
go test -race ./...
go vet ./...

# 全九库构建；以下目录需有对应平台的 IBM 官方 CLI。
CGO_CFLAGS="-I$PWD/data/drivers/ibmdb/clidriver/include" \
CGO_LDFLAGS="-L$PWD/data/drivers/ibmdb/clidriver/lib -Wl,-rpath,$PWD/data/drivers/ibmdb/clidriver/lib" \
go build -tags ibmdb -o data/bin/chen-go ./cmd/chen-go

# 私有 YAML 必须是 0600；不得把密码放命令行或测试报告。
CHEN_READONLY_BACKENDS=/private/path/backends.yml \
CGO_CFLAGS="-I$PWD/data/drivers/ibmdb/clidriver/include" \
CGO_LDFLAGS="-L$PWD/data/drivers/ibmdb/clidriver/lib -Wl,-rpath,$PWD/data/drivers/ibmdb/clidriver/lib" \
go test -tags ibmdb ./internal/engine -run '^TestReadonly(DatabaseMatrix|DriverCancellation|HTTPCancellation|ClickHouseServerCancellation|ClickHouseAutomaticCancellation)$' -v -count=1
```

可用 `CHEN_READONLY_SELECTOR` 只验收一个 selector。测试日志只记录检查项和结果，不输出连接凭据或业务记录。
默认 `Dockerfile-go` 不打包 IBM CLI；Db2 部署需要与运行平台对应的 CLI 和带 `ibmdb` 标签的构建。

## 解析与传输边界

MySQL/PostgreSQL 使用现有原生语法解析器；新增方言的执行路径使用有界语句扫描器，支持常见读写、结构修改和事务语句，检查引号、注释、括号、原文边界和含糊的批处理关键词，由真实服务器验证厂商语法。它不是完整 ANTLR AST，也不是数据库权限的替代品。新方言补全使用共享容错作用域与独立目录查询，不执行或 PREPARE 用户正在输入的 SQL。

认证、连接、目录、补全、提交和结果均走 HTTP；执行状态沿用每会话一条 SSE，没有新增 WebSocket。取消能力按实际驱动返回，未知执行结果不会自动重放。

当前是指定测试版本、账号和 single 模式的验收结果；不等于所有版本、TLS 配置或真实 Core/Wisp 托管部署均已验收。未在本次九类范围内的新数据源仍需独立适配。

## 本次本机体验入口

服务监听 `http://127.0.0.1:8083`，私有测试配置不进入仓库。以下 selector 仅属于本次本机环境：

| 数据库 | 路径 |
| --- | --- |
| 本地 PostgreSQL | `/luna/chen/?selector=local-pg` |
| 测试 MySQL | `/luna/chen/?selector=qa-mysql` |
| 测试 MariaDB | `/luna/chen/?selector=qa-mariadb` |
| 测试 PostgreSQL | `/luna/chen/?selector=qa-pg` |
| 测试 SQL Server | `/luna/chen/?selector=qa-sqlserver` |
| 测试 Oracle | `/luna/chen/?selector=qa-oracle` |
| 测试 Dameng | `/luna/chen/?selector=qa-dameng` |
| 测试 Db2 | `/luna/chen/?selector=qa-db2` |
| 测试 ClickHouse | `/luna/chen/?selector=qa-clickhouse` |
| 测试 OceanBase MySQL | `/luna/chen/?selector=qa-oceanbase` |

依赖核对补充：本机共享 Go 缓存的 `aws-sdk-go v1.44.306`、`golex v1.1.0`、`mathutil v1.6.0` 存在历史修改，整体 `go mod verify` 因此未通过；三者均未出现在本次服务二进制的实际依赖中。未改动用户共享缓存；隔离缓存全量重下载未完成，不能记录为校验通过。
