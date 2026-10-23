# Go 数据库工作区：兼容性与验收

2026-10-23：旧版 Chen 的九类数据库入口已接入 Go 后端与 Luna Monaco。
本轮范围是只读工作区：对象树、查询、表名/字段/别名/JOIN 补全、结果查看和审计；未新增数据写入、结构修改或导入功能。

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
| Dameng | 通过 | 通过 | 通过 | TLS 厂商证书配置尚未接入 |
| Db2 | 通过 | 通过 | 通过 | 需 IBM CLI；暂不支持主动取消已运行查询；TLS 配置尚未接入 |
| ClickHouse | 通过 | 通过 | 通过 | 支持 native/HTTP；数组内 UInt64 保留为精确字符串 |
| OceanBase MySQL 租户 | 通过 | 通过 | 通过 | 使用官方 OceanBase Go 驱动 |
| OceanBase Oracle 租户 | 已实现，未实测 | 已实现及单测 | 未实测 | 提供的 CE 环境无 Oracle 租户，不以 MySQL 测试替代 |

共同验收包括：身份对应的对象树、字段类型、schema 限定表名、别名字段、JOIN 同名字段限定、零行 SELECT、约 150–350 KiB 长文本（含中文/emoji）、超过 JavaScript 安全整数范围的数值、JSON 文本、NULL/空串、二进制十六进制（ClickHouse 测数组），以及真实 standalone 审计落盘。
Oracle 将空字符串视为 NULL，工作区保留数据库本身语义。

Db2 另通过 DBCLOB 中文/emoji、ASCII BLOB、精确 DECIMAL、CLI 原生超时及严格 CGO 指针检查回归。CLI `QueryTimeout=1` 的计数查询约 1.3 秒返回 SQLSTATE 57014，随后连接可复用；服务默认 CLI QueryTimeout 为 60 秒。主动取消能力仍明确为 false，不能把超时等同于随时可取消。

最终九库汇总实测耗时 36.75 秒，全部通过；另跑 MySQL/PostgreSQL 独立容器查询、取消、超时和会话关闭回归通过。Go 全包 race、vet 通过，Luna 88 项单元测试、Web 类型检查、Lint 和静态构建通过。

浏览器额外复核 Oracle 对象树/别名/JOIN/CLOB 分段，SQL Server 方括号补全接受后执行零行查询，Db2 大文本与尾部查找；最终页面展示本地 PG 的纯常量 JOIN、JSON、长文本、二进制样例。九库通用路径由 HTTP 实库 matrix 覆盖，未声称对九库全部 UI 功能分别穷举。

## 本轮实测修复

- SQL Server 连接配置使用驱动完整默认值，修复初始化 panic。
- Dameng 按其实际 DSN 解析规则处理密码，修复错误的 URL 编码。
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
go test -tags ibmdb ./internal/engine -run '^TestReadonlyDatabaseMatrix$' -v -count=1
```

可用 `CHEN_READONLY_SELECTOR` 只验收一个 selector。测试日志只记录检查项和结果，不输出连接凭据或业务记录。
默认 `Dockerfile-go` 不打包 IBM CLI；Db2 部署需要与运行平台对应的 CLI 和带 `ibmdb` 标签的构建。

## 解析与传输边界

MySQL/PostgreSQL 使用现有原生语法解析器；新增方言的执行路径使用有界 SELECT/WITH 语句扫描器，检查引号、注释、括号、原文边界和不支持的写入/批处理关键词，由真实服务器验证厂商语法。它不是完整 ANTLR AST，也不是数据库权限的替代品。新方言补全使用共享容错作用域与独立目录查询，不执行或 PREPARE 用户正在输入的 SQL。

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
