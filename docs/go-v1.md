# Chen Go V1：只读数据库工作区

本版本在 Go 服务与 Luna Monaco 工作区之间实现认证、连接、SQL执行、取消、语义补全、审计的闭环。本次扩展覆盖旧版数据库入口，具体驱动、构建条件和实测范围见[数据库兼容性](database-compatibility.md)。协议路由发生在交换一次性连接Token之前，失败后不会自动把同一Token转交另一个后端。

无需 Core/Wisp 的本机运行方式见 [single 模式](single.md)：配置文件提供数据库信息，通过 URL 的 `selector` 直接打开工作区。

## 启动与路由

需要Go 1.26.3、C编译器（`pg_query_go`依赖CGO）、现有Core/Wisp。Wisp接口固定为`v0.2.12`；线上版本需完成部署验收。

```sh
GOTOOLCHAIN=go1.26.3 go build -o /tmp/chen-go ./cmd/chen-go
/tmp/chen-go --wisp-address 127.0.0.1:9090 --state-dir ./data/go
```

默认只监听`127.0.0.1:8083`。本机Wisp使用明文gRPC；远端Wisp需要`--wisp-ca /path/ca.pem`，或在受保护网络显式设置`--wisp-insecure`。数据库TLS配置来自Core，不能由浏览器覆盖。PostgreSQL支持disable、require、verify-ca、verify-full；prefer/allow暂拒绝，避免默默降级为明文。服务不允许`PGSERVICE`替代Core连接配置。

`Dockerfile-go`保留独立CGO构建，`docker-compose.go.yml`可叠加现有Wisp开发配置；审计和执行意图保存在专用持久卷。该Compose只增加Go服务，反向代理与Luna需同步配置。生产部署仍需实际Core/Wisp、网关和TLS验收。

同源反向代理把`/chen/api/v1/`路由到Go，其余Chen路径继续走Java。示例：

```nginx
location ^~ /chen/api/v1/ {
    proxy_pass http://chen-go:8083;
    proxy_http_version 1.1;
    proxy_set_header Host $http_host;
    proxy_set_header Connection "";
    proxy_buffering off;
    proxy_read_timeout 75s;
}
```

Luna构建时按已验收范围设置`NUXT_PUBLIC_CHEN_GO_V1_PROTOCOLS=mysql,mariadb,postgresql,oracle,sqlserver,dameng,clickhouse,oceanbase`；包含 Db2 时还需要后端 IBM CLI 构建并加入 `db2`。默认空值维持旧入口；它不是运行中自动探测或降级开关。开发代理目标按Luna V1 README设置。浏览器请求使用同源API，不把会话Token放URL。

## 连接与执行

- Core连接Token通过Wisp交换，浏览器不能提交地址、账号密码、DSN或TLS私钥。成功Ping数据库后才记录连接成功。Go会话Token仅在内存持有。
- 创建会话需要稳定随机`request_id`。同连接Token与同请求ID可恢复该次请求的结果；另一个请求ID返回409。认证未知结果不能换新请求ID盲重试。
- 每个控制台固定一个`sql.Conn`，串行执行、元数据查询与补全；事务、临时表、会话变量不会串到其他控制台。数据库实际断线后需显式新建控制台，不自动重放SQL。
- SQL先由方言解析器或只读语句扫描器确定原文边界，再对整个脚本与各原文语句执行Core ACL校验；随后逐句执行原文。执行中再次读取MySQL SQL mode和校验权限。脚本不是隐式事务，前序已完成语句不会因后序失败自动回滚。
- 提交返回202和执行ID。按`request_id`在同控制台去重；相同ID、不同SQL返回409。结果分页只读取已保存的结果，不重新执行SQL。整数和精确数值以字符串传递；NULL、重复列名、列顺序得到保留。
- 取消是独立HTTP动作，通过受控连接调用MySQL KILL QUERY或PostgreSQL pg_cancel_backend，并结合驱动取消。数据库已成功返回的结果保持成功；无法确认发送后结果时为`unknown`，禁止自动重试。`canceled`不表示脚本之前的语句已回滚。

## 传输与API

浏览器V1链路没有WebSocket。认证、连接、SQL提交、取消、分页、目录与补全均为HTTP。每个会话只建立一个可恢复的fetch-SSE，推送执行状态、会话管理和失联事件；结果行通过分页HTTP获取。断开SSE不会取消SQL、提交事务或销毁数据库会话。

所有私有资源都需`Authorization: Bearer <session-token>`或`token`请求头。资源ID不是授权凭据。错误格式统一为`{"error":{"code":"...","message":"..."}}`。

| 方法与路径（前缀`/chen/api/v1`） | 行为 |
| --- | --- |
| GET `/capabilities` | 实际启用的协议与传输能力 |
| POST `/sessions` | `{connect_token,request_id}`，返回`{token,session}` |
| GET / DELETE `/sessions/{sid}` | 状态 / 关闭 |
| GET `/sessions/{sid}/events` | SSE，支持`Last-Event-ID` |
| POST `/sessions/{sid}/consoles` | 可选`{database,schema}`，返回控制台 |
| DELETE `/consoles/{cid}` | 关闭控制台 |
| POST `/consoles/{cid}/executions` | `{sql,request_id,document_version?}`，返回执行状态 |
| GET `/executions/{eid}` | 查询执行及审计记录状态 |
| POST `/executions/{eid}/cancel` | 请求取消，202不等于数据库已取消 |
| GET `/results/{rid}/rows?offset=0&limit=200` | 已物化结果分页 |
| GET `/consoles/{cid}/relations?prefix=...` | 当前权限和连接上下文内的表/视图 |
| GET `/consoles/{cid}/columns?schema=...&table=...` | 列定义 |
| POST `/consoles/{cid}/completion` | `{document,position_utf16,document_version}` |

执行状态：queued、running、cancel_requested、succeeded、failed、canceled、unknown。`statement_results`保存已完成语句的结果集ID或影响行数。`audit_status=recorded`表示本地outbox已持久化，不表示Core已确认收到。failed表示本地审计/状态持久化失败，与SQL执行结果分别展示。

SSE格式为`id: sequence`、`event: type`、`data: {sequence,type,data}`。事件包括execution.updated、console.updated、session.updated、session.closed和reset。恢复快照为`{session,consoles,executions}`。历史不足时发送reset；订阅溢出时断开，由客户端重新建立并查询状态，不重发SQL。

## SQL服务与Monaco

执行解析使用固定版本的TiDB parser和`pg_query_go/v6`（PostgreSQL 17语法）。前期生成/编译/语料验证未使ANTLR grammars-v4成为更合适的执行解析器；ANTLR项目托管语法不等于数据库厂商完整维护的各版本语法。语法、编辑器容错和服务端执行分别承担责任。

Monaco提供编辑、快捷键和补全交互；Go提供当前授权目录、作用域和方言标识符引用规则。补全支持表/列、别名、CTE、派生表、相关子查询、中文/引用名称与基本函数签名；按文档版本丢弃过期响应。用户正在编辑的SQL不会为补全而执行或PREPARE。忙碌控制台返回409，前端保留编辑内容。

完整边界见[SQL服务说明](../internal/sqlservice/README.md)。TiDB语法并非完整MySQL语法；当前不支持客户端DELIMITER、MySQL SELECT INTO用户变量及MariaDB特有RETURNING。复杂函数重载、过程局部变量、动态SQL和完整语义类型推断尚未实现。当前交付不是所有本地客户端功能的替代品。

## 审计、恢复与配额

执行前先把意图写入私有目录并fsync；结束后更新状态。执行和拒绝都会记录Core命令审计；原始结果行不重复写入审计。审计摘要包含执行ID，便于从Core关联本地执行记录。字段脱敏发生在结果缓存之前；驱动不能提供字段来源时采用保守脱敏。元数据访问按控制台与操作类型每分钟记录一次访问摘要，避免逐键审计噪声。

Core不可达时，本地outbox保留审计、会话结束和网关清理意图，后台重试并支持进程重启恢复。它不会重放SQL。Wisp UploadCommand没有幂等键，丢失响应可能产生重复审计；CreateForward丢失完整响应时无法找回未返回的forward ID。这些是当前Wisp协议边界。完整授权与生命周期契约见[JumpServer集成说明](../internal/jumpserver/README.md)。

默认限制：100个会话、每会话8个控制台、每控制台8个排队任务、每会话256条执行历史、每次执行60秒（含排队）、100条语句、2MiB SQL、每结果集10000行、每会话16MiB结果行数据。元数据3秒、补全200候选。行限制不代表数据库执行成本限制；大字段在驱动Scan前可能已分配。结果与Token不跨进程保留；未完成意图的结果为未知，重启后需要重新认证并人工确认数据库实际状态。

状态目录及outbox分别使用0700/0600。执行意图包含SQL，应按审计数据保护。已完成意图目前需按部署保留策略清理；不要删除未完成意图或未投递outbox。容量限制返回明确错误，不静默丢弃历史或重跑查询。

## 验证与放量条件

```sh
GOTOOLCHAIN=go1.26.3 go test -race ./...
GOTOOLCHAIN=go1.26.3 go vet ./...
CHEN_INTEGRATION=1 GOTOOLCHAIN=go1.26.3 go test -race -count=1 -v ./internal/engine -run TestIntegrationDatabaseHTTP
```

实库测试创建并自动清理专用MySQL 8.4/PostgreSQL 16容器，使用随机localhost端口；不连接现有业务库。Wisp测试使用真实生成的gRPC客户端/服务端内存通信，并注入Core响应。Luna同时提供Monaco组件smoke和真实工作区联调脚本。详细证据以当前运行记录为准。

放量前仍需现网版本Core/Wisp、真实资产权限、网关、数据库TLS、管理员锁定/踢出、审计落Core和反向代理空闲重连验收。审批/NotifyWarning交互、Java专有ACL正则、人脸监控、非密码认证仍明确拒绝，需在连接Token交换前选择旧Chen。本次新增方言只实现读取工作流；导入导出、结构编辑及完整管理工作流继续保留旧入口。

以下为初版 MySQL/PostgreSQL 的历史验收记录；当前扩展的结果以数据库兼容性文档为准。

初版开发验收：干净模块缓存`go mod verify`通过；Go完整race/实库与Web+Electron类型检查、Nuxt生产构建通过。MySQL和PostgreSQL分别在真实Nuxt工作区的Chromium验收中完成认证、语义补全、精确值查询、本地审计、取消与关闭。报告保存在Luna `output/chen-v1-browser/mysql/`和`postgresql/`，其中明确标注Core/Wisp为测试边界。

最终回归使用单独下载的干净模块缓存，`go mod verify`、全包`go test -race`和`go vet`均通过。实库HTTP整套20.76秒：MySQL8.4普通账号13.59秒，PostgreSQL16普通账号7.17秒；均包含服务器侧取消、超时和关闭验证。`Dockerfile-go`已成功构建Linux arm64镜像`chen-go:v1-local`；真实启动验证健康接口、能力接口、UID10001、审计目录0700及SIGTERM退出码0。临时数据库容器、浏览器fixture、Nuxt测试服务和镜像smoke卷均已清理。
