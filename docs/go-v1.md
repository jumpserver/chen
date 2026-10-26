# Chen Go V1：数据库查询工作区

CSV/XLSX 后台导出：[流程、限制与验证](exports.md)。数据编辑、CSV 导入及结构修改：[写入流程](writes.md)。

本版本在 Go 服务与 Luna Monaco 工作区之间实现认证、连接、SQL执行、取消、语义补全、审计的闭环。本次扩展覆盖旧版数据库入口，具体驱动、构建条件和实测范围见[数据库兼容性](database-compatibility.md)。协议路由发生在交换一次性连接Token之前，失败后不会自动把同一Token转交另一个后端。

无需 Core/Wisp 的本机运行方式见 [single 模式](single.md)：配置文件提供数据库信息，通过 URL 的 `selector` 直接打开工作区。

## 启动与路由

需要Go 1.26.3、C编译器（`pg_query_go`依赖CGO）、现有Core/Wisp。Wisp接口固定为`v0.2.15`；线上版本需完成部署验收。

```sh
GOTOOLCHAIN=go1.26.3 go build -o /tmp/chen-go ./cmd/chen-go
/tmp/chen-go --wisp-address 127.0.0.1:9090 --state-dir ./data/go
```

默认只监听`127.0.0.1:8083`。本机Wisp使用明文gRPC；远端Wisp需要`--wisp-ca /path/ca.pem`，或在受保护网络显式设置`--wisp-insecure`。数据库TLS配置来自Core，不能由浏览器覆盖。PostgreSQL支持disable、require、verify-ca、verify-full；prefer/allow暂拒绝，避免默默降级为明文。服务不允许`PGSERVICE`替代Core连接配置。

正式构建已切换为 `Dockerfile-base` → `Dockerfile` → `Dockerfile-ee`，`make image-ee` 可完整构建。生产镜像及 `./start.sh managed` 保持 8082；独立 Go CLI 与 `make single` 默认 8083。Docker 数据保存在 `/opt/chen/data`。启动、Db2、多架构和验收步骤见 [构建文档](build.md)。

同源反向代理把 `/chen/api/v1/` 路由到 Go；正式镜像不再启动 Java。示例：

```nginx
location ^~ /chen/api/v1/ {
    proxy_pass http://chen:8082;
    proxy_http_version 1.1;
    proxy_set_header Host $http_host;
    proxy_set_header Connection "";
    proxy_buffering off;
    proxy_read_timeout 75s;
}
```

Luna构建时按已验收范围设置`NUXT_PUBLIC_CHEN_GO_V1_PROTOCOLS=mysql,mariadb,postgresql,oracle,sqlserver,dameng,clickhouse,oceanbase`；包含 Db2 时还需要后端 IBM CLI 构建并加入 `db2`。默认启用已支持的 Go 协议；显式设置空值才保留旧入口。该列表不是运行中自动探测或降级开关。开发代理目标按Luna V1 README设置。浏览器请求使用同源API，不把会话Token放URL。

## 连接与执行

- Core连接Token通过Wisp交换，浏览器不能提交地址、账号密码、DSN或TLS私钥。成功Ping数据库后才记录连接成功。Go会话Token仅在内存持有。
- 创建会话需要稳定随机`request_id`。同连接Token与同请求ID可恢复该次请求的结果；另一个请求ID返回409。认证未知结果不能换新请求ID盲重试。
- 每个控制台固定一个`sql.Conn`，串行执行、元数据查询与补全；事务、临时表、会话变量不会串到其他控制台。数据库实际断线后需显式新建控制台，不自动重放SQL。
- SQL先由方言解析器或保守语句扫描器确定原文边界，再对整个脚本与各原文语句执行Core ACL校验；随后逐句执行原文。执行中再次读取MySQL SQL mode和校验权限。脚本不是隐式事务，前序已完成语句不会因后序失败自动回滚。
- 提交返回202和执行ID。按`request_id`在同控制台去重；相同ID、不同SQL或不同复核选择返回409。结果分页只读取已保存的结果，不重新执行SQL。整数和精确数值以字符串传递；NULL、重复列名、列顺序得到保留。
- 取消是独立HTTP动作，通过受控连接调用MySQL KILL QUERY、PostgreSQL pg_cancel_backend或按精确query_id取消ClickHouse查询，并结合驱动取消。SQL Server只有驱动确认attention应答的返回路径才记录已取消；达梦和Db2暂不提供运行中主动取消。数据库已成功返回的结果保持成功；无法确认发送后结果时为`unknown`，禁止自动重试。`canceled`不表示脚本之前的语句已回滚。

## 命令复核与会话控制

- 命中拒绝规则时，整个脚本不执行；命中复核规则先返回`command_review_required`，用户明确点击“申请复核”才创建Core工单。工单绑定该次执行的原始SQL快照，修改编辑器不会改变待审批SQL。同一脚本的同一ACL/命令组只申请一次复核。
- 工单批准后再次检查会话权限，再执行原始SQL。驳回、取消、审批超时、会话暂停或关闭都不执行待审SQL。等待审批默认最多30分钟，单独计时，不消耗60秒SQL执行期限。等待期间可取消，即使数据库不支持运行中取消。
- Core目前保存最多4090个Unicode字符的工单SQL，超长脚本明确拒绝复核，避免仅审批前缀却执行完整脚本。复核没有自动提交、自动批准或未知结果后重新创建工单的行为。
- `warning`执行并记录风险审计；`notify_and_warn`先返回`command_warning_confirmation_required`，用户确认固定 SQL 后携带`confirm_warnings: true`重新提交。确认不会跳过拒绝或复核规则。只有实际送往数据库的语句才通知；混合脚本中的告警另保留对应审计，避免被已批准记录掩盖通知。
- 管理员暂停会阻止查询、目录和补全访问，并请求取消正在执行或等待审批的任务。恢复只解除暂停，不自动重放。踢出会话后关闭连接，保留页面SQL草稿供用户查看。
- Go到Wisp的任务流中断时会话自动暂停，恢复后保留独立的管理员锁定状态。`state_reason`区分控制连接中断和管理员暂停。Wisp v0.2.15未暴露Wisp到Core的上游WebSocket健康状态，这一段不具备同等断线检测。

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
| POST `/consoles/{cid}/executions` | `{sql,request_id,document_version?,submit_review?,confirm_warnings?}`，返回执行状态 |
| GET `/executions/{eid}` | 查询执行及审计记录状态 |
| POST `/executions/{eid}/cancel` | 请求取消，202不等于数据库已取消 |
| GET `/results/{rid}/rows?offset=0&limit=200` | 已物化结果分页 |
| GET `/consoles/{cid}/relations?prefix=...` | 当前权限和连接上下文内的表/视图 |
| GET `/consoles/{cid}/columns?schema=...&table=...` | 列定义 |
| POST `/consoles/{cid}/completion` | `{document,position_utf16,document_version}` |

执行状态：queued、waiting_review、running、cancel_requested、succeeded、failed、canceled、unknown。`review`包含审批状态、工单链接、复核人和期限；只有明确申请才返回待审工单。`statement_results`保存已完成语句的结果集ID或影响行数。`audit_status=recorded`表示本地outbox已持久化，不表示Core已确认收到。failed表示本地审计/状态持久化失败，与SQL执行结果分别展示。

SSE格式为`id: sequence`、`event: type`、`data: {sequence,type,data}`。事件包括execution.updated、console.updated、session.updated、session.closed、export.updated、ai.message和reset。恢复快照为`{session,consoles,executions}`。历史不足时发送reset；订阅溢出时断开，由客户端重新建立并查询状态，不重发SQL。

## SQL服务与Monaco

执行解析使用固定版本的TiDB parser和`pg_query_go/v6`（PostgreSQL 17语法）。前期生成/编译/语料验证未使ANTLR grammars-v4成为更合适的执行解析器；ANTLR项目托管语法不等于数据库厂商完整维护的各版本语法。语法、编辑器容错和服务端执行分别承担责任。

Monaco提供编辑、快捷键和补全交互；Go提供当前授权目录、作用域和方言标识符引用规则。补全支持表/列、别名、CTE、派生表、相关子查询、中文/引用名称与基本函数签名；按文档版本丢弃过期响应。用户正在编辑的SQL不会为补全而执行或PREPARE。忙碌控制台返回409，前端保留编辑内容。

完整边界见[SQL服务说明](../internal/sqlservice/README.md)。TiDB语法并非完整MySQL语法；当前不支持客户端DELIMITER、MySQL SELECT INTO用户变量；MariaDB RETURNING 由保守扫描器回退支持。复杂函数重载、过程局部变量、动态SQL和完整语义类型推断尚未实现。当前交付不是所有本地客户端功能的替代品。

## dev 功能衔接

- SQL AI 使用 Core 已启用的模型配置，通过 HTTP 请求及当前会话 SSE 返回消息；schema 工具只访问当前控制台权限，生成 SQL 以提案形式插入编辑器，不自动执行。
- 对象详情提供列默认值、主外键和索引，DDL 单独按需加载；PostgreSQL 结构重建与原生视图定义分别标明来源，并过滤当前账号无权查看的列及其依赖对象。
- 执行计划只准备不运行的 EXPLAIN SQL，随后走普通执行、ACL、告警、复核和审计路径；不自动使用 ANALYZE。
- Oracle 的 SYSDBA 选项由 Core 连接设置或 single 配置决定；用户名 SYS 不再隐式取得 SYSDBA。SQL Server 尊重平台加密配置与资产 TLS 校验。

## 审计、恢复与配额

执行前先把意图写入私有目录并fsync；结束后更新状态。执行和拒绝都会记录Core命令审计；原始结果行不重复写入审计。审计摘要包含执行ID，便于从Core关联本地执行记录。字段脱敏发生在结果缓存之前；驱动不能提供字段来源时采用保守脱敏。元数据访问按控制台与操作类型每分钟记录一次访问摘要，避免逐键审计噪声。

会话关闭时将 SQL 和已脱敏状态摘要生成为 asciinema v2 录像，通过 Wisp 上传 Core；不在录像重复存放原始结果行。录像成功上传后才删除本地副本；丢失响应后可重建压缩文件重试。Core不可达时，本地outbox保留审计、录像、会话结束和网关清理意图，后台重试并支持进程重启恢复。它不会重放SQL。Wisp UploadCommand没有幂等键，丢失响应可能产生重复审计；CreateForward丢失完整响应时无法找回未返回的forward ID。这些是当前Wisp协议边界。完整授权与生命周期契约见[JumpServer集成说明](../internal/jumpserver/README.md)。

默认限制：100个会话、每会话8个控制台、每控制台8个排队任务、每会话256条执行历史、每次执行60秒（含排队，不含已创建工单的审批等待）、100条语句、2MiB SQL、每结果集10000行、每会话16MiB结果行数据。元数据3秒、补全200候选。行限制不代表数据库执行成本限制；大字段在驱动Scan前可能已分配。结果与Token不跨进程保留；未完成意图的结果为未知，重启后需要重新认证并人工确认数据库实际状态。

状态目录及outbox分别使用0700/0600。执行意图包含SQL，应按审计数据保护。已完成意图目前需按部署保留策略清理；不要删除未完成意图或未投递outbox。容量限制返回明确错误，不静默丢弃历史或重跑查询。

## 验证与放量条件

```sh
GOTOOLCHAIN=go1.26.3 go test -race ./...
GOTOOLCHAIN=go1.26.3 go vet ./...
CHEN_INTEGRATION=1 GOTOOLCHAIN=go1.26.3 go test -race -count=1 -v ./internal/engine -run TestIntegrationDatabaseHTTP
```

实库测试创建并自动清理专用MySQL 8.4/PostgreSQL 16容器，使用随机localhost端口；不连接现有业务库。Wisp测试使用真实生成的gRPC客户端/服务端内存通信，并注入Core响应。Luna同时提供Monaco组件smoke和真实工作区联调脚本。详细证据以当前运行记录为准。

放量前仍需现网版本Core/Wisp、真实资产权限、网关、数据库TLS、管理员锁定/踢出、审计落Core和反向代理空闲重连验收。Java专有ACL正则和非密码认证仍明确拒绝，需在连接Token交换前选择旧Chen。带人脸监控令牌的连接先向 Wisp 注册，失败即关闭会话；真实摄像头流程仍需部署验收。表格写入、CSV 导入及结构编辑已接入，详细事务与类型边界见[写入说明](writes.md)。UI 范围不能代替数据库权限或 Core ACL，部署只读访问应使用只读数据库账号。

### 2026-10-26 托管模式实测

使用本机隔离Core数据库、独立Redis、Wisp v0.2.15及专用Celery worker，未改现有Core数据库；测试账号、资产、授权、工单和合成SELECT均属于该隔离环境。`TestManagedLive`通过真实HTTP/gRPC验证：

- 普通操作员连接Token、资产/账号授权、复制/粘贴禁用标志；撤销授权后拒绝新连接。
- SQL允许/拒绝，整批提前拒绝，显式复核、原始SQL快照、批准、驳回、取消和超时；暂停待审批会关闭工单并阻止SQL。
- 管理员暂停、恢复、踢出，以及Core会话结束记录。
- Core命令审计及实际风险等级、NotifyWarning显式确认与站内通知；未执行的后句单独告警及解析失败不会误发告警。
- 字段、NULL及无法确定原列的别名脱敏。
- 会话结束后 Core 持久化的 gzip 录像可重新打开并解码为 asciinema v2。

`TestManagedLivePermissionRevocation`另观察了Wisp原有五分钟检查周期实际发出的撤权事件，保留现有十分钟宽限；不是实时撤权保证。真实数据库与API验证之外，Luna用实际Nuxt/Monaco配合模拟HTTP/SSE验证审批与暂停竞态。没有把模拟控制浏览器测试描述为真实Core页面端到端。

网关转发、TLS资产配置、审计断网重启恢复和控制流断开/恢复有契约或故障注入测试，未在本轮做真实部署验收。人脸监控有 Wisp 注册失败补偿契约测试，尚未做摄像头部署验收；Java专有正则和非密码认证仍需旧入口。邮件未对外发送，测试通知限于隔离Core站内信和本地邮件后端。

以下为初版 MySQL/PostgreSQL 的历史验收记录；当前扩展的结果以数据库兼容性文档为准。

初版开发验收：干净模块缓存`go mod verify`通过；Go完整race/实库与Web+Electron类型检查、Nuxt生产构建通过。MySQL和PostgreSQL分别在真实Nuxt工作区的Chromium验收中完成认证、语义补全、精确值查询、本地审计、取消与关闭。报告保存在Luna `output/chen-v1-browser/mysql/`和`postgresql/`，其中明确标注Core/Wisp为测试边界。

最终回归使用单独下载的干净模块缓存，`go mod verify`、全包`go test -race`和`go vet`均通过。实库HTTP整套20.76秒：MySQL8.4普通账号13.59秒，PostgreSQL16普通账号7.17秒；均包含服务器侧取消、超时和关闭验证。`Dockerfile-go`已成功构建Linux arm64镜像`chen-go:v1-local`；真实启动验证健康接口、能力接口、UID10001、审计目录0700及SIGTERM退出码0。临时数据库容器、浏览器fixture、Nuxt测试服务和镜像smoke卷均已清理。
