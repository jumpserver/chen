# Chen single：从配置文件直接连接数据库

`single` 独立运行 Go 后端和 Luna Monaco 工作区，不创建 Wisp 客户端，不调用 Core API。数据库地址、账号、密码和 TLS 配置只从本地 YAML 读取，浏览器 URL 只指定 `selector`。支持旧版数据库家族；Db2 需要 IBM CLI 与 `ibmdb` 构建标签，详见[数据库兼容性及实测](database-compatibility.md)。

## 构建和启动

需要 Go 1.26.3、C 编译器、Node.js 24.14+ 和 Luna 所声明的 pnpm 版本。以下示例假定 Chen、Luna 为相邻目录。

```sh
# 在 Luna 目录生成静态工作区
cd ../luna
pnpm install --frozen-lockfile
pnpm generate:chen

# 在 Chen 目录构建 Go 程序
cd ../chen
mkdir -p data/bin
GOTOOLCHAIN=go1.26.3 go build -o data/bin/chen-go ./cmd/chen-go
cp conf/standalone_backends_example.yaml data/standalone_backends.yml
chmod 600 data/standalone_backends.yml
# 编辑 data/standalone_backends.yml，填写自己的数据库信息

./data/bin/chen-go single \
  --backends data/standalone_backends.yml \
  --web-dir ../luna/.output/public \
  --state-dir data/single
```

然后打开：

```text
http://127.0.0.1:8083/luna/chen/?selector=local-mysql
http://127.0.0.1:8083/luna/chen/?selector=local-pg
```

根地址 `http://127.0.0.1:8083/?selector=local-mysql` 也会跳转到相同工作区。`standalone` 是 `single` 的别名，`-b` 是 `--backends` 的别名；也支持 `chen-go --single ...`。不带 `single` 时继续使用托管模式。只需要 HTTP API 时可省略 `--web-dir`。

Go 直接提供 `/luna/` 下的生成文件和 `/chen/api/v1/`，运行时不需要 Node.js、Nuxt 开发服务器或额外反向代理。静态文件须使用默认 `/luna/` base 构建。编辑器 worker、图标和语言资源均来自本地静态目录。

更新页面时先完成 `pnpm generate:chen`，再重启 Chen。Nuxt 会替换输出目录，正在运行的安全文件根仍指向旧目录；仅刷新浏览器不能切换该目录。

## 配置

```yaml
version: 1
backends:
  - name: Local MySQL
    selector: local-mysql
    protocol: mysql
    host: 127.0.0.1
    port: 3306
    user: chen
    password: replace-with-database-password
    database: app
    use_ssl: false
  - name: Local PostgreSQL
    selector: local-pg
    protocol: postgresql
    host: 127.0.0.1
    port: 5432
    user: chen
    password: replace-with-database-password
    database: app
    use_ssl: false
```

`selector` 全局唯一、区分大小写、精确匹配；未知或缺失 selector 不连接任何默认数据库。配置在启动时校验和读取，修改后重启生效。数据库权限由配置的数据库账号决定；浏览器不能覆盖地址或凭据。与 Magnus 的协议代理相比，这里不需要客户端数据库密码，因为客户端是浏览器工作区。

配置文件采用严格 YAML 校验，不接受未知字段、多个文档、重复 selector、不支持的协议、符号链接和超过 1 MiB 的文件。默认要求文件不向组或其他用户开放权限（建议 `0600`）；特殊挂载环境可显式使用 `--allow-insecure-backend-file`。TLS 选项与完整示例见 [配置示例](../conf/standalone_backends_example.yaml)。

single 不验证 Core 用户身份，selector 是连接名称，不是密码；默认仅监听 `127.0.0.1:8083`，且拒绝陌生 Host 对本机监听器的访问。若显式改为网络监听，应由部署方控制访问范围或通过认证反向代理接入。

## 会话、审计和传输

页面先确认能力接口为 `mode: single`，再发送 `POST /chen/api/v1/sessions` 的 `{selector, request_id}`。服务端返回独立的会话 token，后续请求仍校验 token 和资源归属。托管模式拒绝 selector，single 模式拒绝 Core token；两者不会互相降级。

同一浏览器请求重试复用相同会话；同一 selector 在不同标签页或重新打开页面时建立独立会话和数据库连接，不共享事务、临时表或查询结果。每个控制台保持自己的物理连接；取消、超时、关闭会话的行为与 Go V1 一致。单机会话默认闲置 15 分钟后关闭，最长持续 8 小时。

认证、查询、取消、分页和补全使用 HTTP，每会话一个 SSE 推送状态，没有业务 WebSocket。SSE 中断不重放 SQL。

本地审计以独立 JSON 文件保存在 `--state-dir` 下的 `audit/`，执行意图保存在 `executions/`。生命周期、元数据访问摘要、SQL 及执行结果状态落盘并同步写入；不保存配置中的数据库凭据或原始结果行。SQL 原文本身仍应按审计数据保护。`audit_status=recorded` 表示本地持久化完成。single 不具有 Core ACL、字段脱敏、管理员远程控制或 Core 审计投递能力。

会话和结果保存在进程内；重启后重新连接，不恢复事务或自动重放 SQL。旧执行意图可用于人工核对未知结果。资源配额、语法边界和取消语义沿用 [Go V1 说明](go-v1.md)。

## 验证

```sh
GOTOOLCHAIN=go1.26.3 go test -race ./...
GOTOOLCHAIN=go1.26.3 go vet ./...
CHEN_INTEGRATION=1 GOTOOLCHAIN=go1.26.3 go test -race -count=1 -v ./internal/engine -run TestIntegrationSingle
```

实库测试自行启动和清理专用 MySQL 8.4、PostgreSQL 16 容器，使用普通数据库账号，覆盖 selector、会话隔离、查询、补全、取消及本地审计。静态工作区浏览器验收见 Luna 的 Chen V1 README。

初版 2026-10-23 本机验收（当前多库扩展另见兼容性文档）：两库实库 HTTP 集成测试、全包 `-race`、`go vet`、干净模块缓存校验通过。真实 `chen-go single` 进程直接提供生成的 Luna 静态文件，两库均通过 Chromium 的 URL 直连、Monaco 别名补全、精确整数/中文/NULL 查询、双标签页独立会话、取消及关闭验收；网络记录中 Core/Wisp 请求和 WebSocket 均为零。审计目录 `0700`、文件 `0600`，连接生命周期和查询记录实际落盘；进程正常退出。报告位于 Luna `output/chen-single-browser/mysql/report.json` 与 `postgresql/report.json`。

## 新增方言配置

`protocol` 支持 `mysql`、`mariadb`、`postgresql`、`oracle`、`sqlserver`、`db2`、`dameng`、`clickhouse`、`oceanbase`。OceanBase 必须同时设置 `tenant_mode: mysql` 或 `oracle`，不能把 Oracle 租户交给 Oracle 原生驱动。`oceanbase-mysql` / `oceanbase-oracle` 也是明确的兼容写法。

- Oracle 的 `database` 为 service name；可用 `schema` 指定当前 schema。
- Dameng 的 `schema` 指定模式，不应把模式误当成 Oracle service。
- Oracle 可显式设置 `use_sysdba: true`；默认普通登录，用户名 SYS 不会隐式提升权限。
- ClickHouse 的 `transport` 可为 `native` 或 `http`；默认按常用端口选择。
- `can_cancel: false` 表示当前驱动无法中断已经开始的查询，界面不会承诺能够取消；Db2 当前属于此范围。
- 此配置文件含数据库凭据，必须保持 `0600`；浏览器只使用 selector，不能指定任意地址或账号。
