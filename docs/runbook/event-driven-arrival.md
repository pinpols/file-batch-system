# 事件驱动到达(Event-Driven Arrival)

> 路线图 Phase 4.1 v1。通过到达通知提前触发 import 入口扫描，**不绑定云厂商、不绕过 Trigger/Orchestrator**。通知本身不保证文件立即登记或启动。

## 当前状态

import 到达发现默认保留轮询:`ImportIngressScanner` 在扫描范围已声明为 ACTIVE 时，默认完成一轮后等待 30s（`batch.worker.import.scanner.poll-interval-millis`）再扫描配置前缀；候选对象通过稳定性等准入后登记到 `file_record`，后续由 orchestrator 到达分组/触发机器推进。默认稳定窗口为 30s，扫描间隔不等于登记或触发的时延上限。

事件驱动到达已经作为 **v1 加速层** 落地，但默认关闭。开启 `batch.worker.import.scanner.event-arrival.enabled=true` 后，对象存储或上游网关可以在对象落地时通知 import worker 提前扫描；对象仍须出现在本轮扫描范围内并通过准入检查。

事件驱动不替代轮询。事件丢失或扫描在途时仍依赖后续扫描；完整发现周期可能跨多个 tick，处理时间还受存储、稳定窗口、配置和排队影响，不能据此给出固定时延上界。

## 机制(v1:事件驱动「提前扫」)

新增一个 **默认关** 的内部 inbound 端点:

```
POST /internal/import/events/object-arrival
Content-Type: application/json

{ "tenantId": "t1", "bucket": "ingress", "objectKey": "ingress/import-20260620-orders.csv" }
```

- 请求必须携带平台内部共享密钥:

```
X-Internal-Secret: <BATCH_INTERNAL_SECRET>
```

- 两个扫描开关都开启时，收到通知会调用 `ImportIngressScanner.scan()`；对象是否被发现和登记取决于本次扫描结果。
- 如果扫描器未获得租约或该范围处于维护阶段，响应为 `triggered=false`、`reason=scan-lease-unavailable`，不增加扫描启动计数；仅取得租约并启动扫描才返回 `triggered=true`。
- 通知体字段**全部可选**，仅用于日志和定位；实际发现仍由扫描器按配置前缀推进持久分页周期，不按通知中的对象 key 直接登记。`batch-size` 默认 200，每次调用在发现、清单检查、成员处理及旧观察清理各阶段分别最多推进该数量；阶段未完成时由后续调用继续。
- **在途守护**:`AtomicBoolean` 让密集通知合并为「至多一次在途扫描」,事件风暴下不会压垮扫描器。
- 扫描器只做「发现 + 登记」,**不直接起任务**——起任务仍走 Trigger/Orchestrator 主链,事件驱动不改这条边界。

> 轮询调度器 `scheduledScan` 保持开启作为回退；定时和事件入口均使用数据库中的共享租约及等待队列。排障时应同时核对扫描前缀、周期阶段、租约、规则摘要及稳定窗口，不能仅凭轮询开启认定对象已经登记。

## 事件源(不绑定云厂商)

任何「对象落地能发 HTTP 通知」的来源都可对接:

- **AWS S3** → EventBridge / SNS → HTTP 订阅
- **MinIO** → bucket notification(webhook)
- **阿里云 OSS / 腾讯云 COS** → 事件通知 → 函数/网关转发
- **自建 poller / 上游推送网关** → 直接 POST

映射「对象 → 该扫哪个 worker/前缀」由部署侧路由(通知发到对应 import worker 实例);v1 不在通知体里做 tenant/job 解析(发现仍按 worker 配置前缀)。

## 扫描分页、恢复与配置切换

扫描器交替推进正向游标与从头回扫游标，并按窗口检查本页清单及处理一批已发现对象；回扫每轮的页数预算逐步扩大，持续追加时无需等前缀末页也可登记已就绪对象。成员处理轮次使用数据库中实际存在的对象 ID 上界，轮次内新增记录留给后续轮次，不依赖某个终止对象仍然存在。正向完整遍历后，先分批处理遍历结束时的完整观察快照，再分批清理旧观察。同一物理存储位置、bucket 与部署命名空间内的不同前缀共享租约，各前缀保留独立进度；定时和事件调用均经过共享租约。对象读取不占用数据库行锁，登记文件记录、审计和推进位置前须验证租约及围栏；长文件摘要计算按时间续租。单次对象存储调用若长时间阻塞，不能在该次调用内续租；过期的执行者不能提交后续登记，依赖后续调用重试。

完成标记按完整对象 key 查询，不要求与数据文件在同一页；批次清单和成员可分属不同页。启用清单驱动时，扫描器按绑定的唯一权威 key 查询清单并核对租户、业务日、组和成员；未绑定、清单缺失、未稳定或内容不匹配均等待，不退回静态组登记。对象登记前会复核观察到的 size 和 etag，变化后重新观察；批次清单变化也会延后相关成员，等待后续周期处理。对象存储读取与数据库提交不是一个原子快照，不能将复核视为对所有并发覆盖的强一致保证。

同一物理 bucket 的多个前缀通过持久等待队列串行竞争共享租约；等待请求过期后才允许后续前缀跳过，低频配置或节点故障会增加让位时间。该机制提供有界批次和可达历史位置的轮转，不保证超出处理吞吐的持续积压有界，也不保证固定发现时延。默认文件系统后端的精确 key 版本检查仍使用前缀列举，可能扫描所在目录；目录规模和对象存储请求量应结合目标环境实测。停止使用的扫描范围协调状态不会自动按配置变更整体回收。

同一存储的扫描范围允许重叠；父范围不会处理更具体的 ACTIVE 或 RETIRED 子范围内的对象，也不会将子范围声明的目录外权威清单当作自己的导出触发清单。切换前父范围已观察但尚未登记的对象同样按当前子范围归属跳过；父范围自身对象仍按原规则处理。声明切换须遵循下方的受控停用流程，不支持扫描运行中热切换。

V377 创建扫描状态、对象观察和物理存储租约表，V382 创建 Import 与对象对账共用的入口归属声明。scanner 只处理平台库存在 `ACTIVE` 声明的范围；声明缺失、退休或释放时不列举对象，事件通知返回 `scanner-ownership-inactive`。使用受限 Import 角色时，管理员还需按[数据库脚本目录](../../scripts/db/README.md#入站范围归属声明)执行扫描状态和共享声明的读取授权；扫描失败应检查迁移及角色授权，不应切换到业务库或使用高权限账号绕过。

同一前缀的扫描语义配置变化会改变规则摘要；同范围旧配置和新配置不能并行认领。轮询间隔和批大小不改变该摘要，租户回退、完成标记、稳定窗口、文件组及渠道绑定等语义配置会改变。**常规配置变更和回滚需要受控切换，不会自动清除状态**：

1. 暂停相关上游投递，停止受影响的 Import 扫描器、事件调用和对象对账；确认没有活动扫描租约。旧版组件不读取共享声明，不能与新版本混跑。根据 bucket、前缀和 `storage_id` 在平台库定位唯一 `scope_id`，核对目标后再操作。
2. 由扫描状态表所有者或获授权的管理员运行 [维护脚本](../../scripts/db/import-ingress-scan-maintenance.sql)，先使用默认 `preview`；确认范围、对象样本数和租约后执行 `action=begin`。维护阶段会阻止该范围继续认领。
3. 重复执行 `action=clear-page`，每次最多删除 `max_rows` 条可重建观察，直至剩余为零；再执行 `action=activate`。中断后可继续清理。该脚本不删除业务 `file_record` 或审计记录，但重新发现对象和稳定窗口会增加恢复时间。
4. 按[归属声明操作](../../scripts/db/README.md#入站范围归属声明)完成声明和绑定的受控切换，确认声明已 ACTIVE，再启动新版本对象对账与 scanner，最后恢复上游。激活后第一个认领者写入规则摘要；若旧配置误先启动，新配置会报规则不一致，需停止并重新执行受控切换。回滚前也须暂停上游并停止相关组件，核对旧版不识别共享声明的风险。此流程不支持扫描进行中热切换声明。

维护脚本示例（连接参数和 `SCAN_SCOPE_ID` 由管理员按实际环境提供）：

```bash
psql "$PLATFORM_DATABASE_URL" -X -v ON_ERROR_STOP=1 -v scope_id="$SCAN_SCOPE_ID" -f scripts/db/import-ingress-scan-maintenance.sql
psql "$PLATFORM_DATABASE_URL" -X -v ON_ERROR_STOP=1 -v scope_id="$SCAN_SCOPE_ID" -v action=begin -f scripts/db/import-ingress-scan-maintenance.sql
psql "$PLATFORM_DATABASE_URL" -X -v ON_ERROR_STOP=1 -v scope_id="$SCAN_SCOPE_ID" -v action=clear-page -v max_rows=200 -f scripts/db/import-ingress-scan-maintenance.sql
psql "$PLATFORM_DATABASE_URL" -X -v ON_ERROR_STOP=1 -v scope_id="$SCAN_SCOPE_ID" -v action=activate -f scripts/db/import-ingress-scan-maintenance.sql
```

`clear-page` 需要重复执行，直到返回的剩余样本数为零；`activate` 会拒绝未清空的范围。预览和清理后的计数最多读取 `max_rows + 1` 条，达到上限只说明至少还有这么多条，不是全量总数。两实例指向同一物理存储时应使用一致的 backend、endpoint 或文件系统挂载路径、bucket 和部署命名空间配置；不同别名即使实际指向同一存储也不会自动合并租约。

### 权威清单绑定与迁移

启用 `batch-manifest-enabled` 前，通过平台库[归属声明脚本](../../scripts/db/README.md#入站范围归属声明)为每个租户的数据目录声明文件组和唯一权威清单 key 模板。示例 `manifests/tenant-a/orders-{bizDateCompact}.batch.json` 在业务日 `2026-11-09` 指向 `manifests/tenant-a/orders-20261109.batch.json`。scanner 每轮读取 ACTIVE 声明中的启用绑定，绑定和 scanner 前缀、桶及存储身份不一致或绑定无效时拒绝扫描。

每项目录必须位于 scanner 前缀下并以 `/` 结尾；同一租户及目录不得重复，嵌套目录按最长前缀选择。模板是 bucket 内完整逻辑 key，必须包含且仅包含一个 `{bizDate}`（`yyyy-MM-dd`）或 `{bizDateCompact}`（`yyyyMMdd`），并以配置的批次清单后缀结尾；不支持其他占位符。数据文件业务日仍由现有 `bizDatePattern` 或 `defaultBizDate` 确定。权威清单显式声明日期无效时不会回退；未声明日期时，只有清单 key 按现有日期规则或默认业务日能确定且与文件日期一致才可匹配。清单声明的租户、文件组和成员名也必须与绑定和文件一致。

该配置收紧了原有的任意清单发现与静态组回退：即使同一目录有其他内容匹配的清单，也只有绑定 key 有权触发该文件；对于 scanner 尚未登记的新文件，目录未绑定或权威清单缺失、未稳定、内容不符时文件等待。**迁移前已由对象对账登记的历史文件可能仍然可见，即使未通过当前清单校验**；切换前须按下节核查。等待原因随状态变化记录，日志使用对象摘要而非原始文件路径。历史任意命名清单可通过将其实际日期化路径填入模板迁移；不能表示为固定目录与日期模板的上游，需要先调整交付约定。无输入的 manifest-only 导出清单仍通过既有清单后缀单独发现：路径包含租户时须与清单声明一致；根目录无租户时先使用清单声明，再回退 Worker 租户。清单日期缺失或无效时沿用路径日期或默认业务日回退，均无法确定时等待。

#### 历史对账文件的切换核查

V378 为迁移前已有文件保留历史回退资格。受管入口中已由旧对象对账登记、但尚未通过 scanner 准入的历史记录，仍可能参与 FILE_ARRIVAL 和文件组到达治理；归属声明不会自动删除或重新校验这些记录。`legacy_channel_fallback` 也用于渠道兼容，不应为改变清单行为直接修改该标志。

按上文受控切换流程暂停受影响的上游、扫描器、事件入口和对象对账，并确认在途 WAIT 与到达组不会继续处理这些文件。使用对目标租户有完整读取权限的只读账号，结合目标环境的实际存储身份、bucket、租户和数据目录核查旧对账记录。`file_record` 不保存 `storage_id`，仅凭 bucket 和路径不能证明物理存储身份；行级权限导致结果不可见时，零结果不能证明没有候选。V378 执行前没有 `legacy_channel_fallback` 字段，先按租户、bucket、目录和 `metadata_json` 中的 `reconciled=true` 清点；迁移完成后、恢复处理前可按下例进一步缩小范围：

```sql
-- psql 变量 tenant_id、bucket、prefix 由管理员按已确认的范围提供。
SELECT id, tenant_id, file_name, file_status, storage_bucket, storage_path, created_at
FROM batch.file_record
WHERE tenant_id = :'tenant_id'
  AND storage_bucket = :'bucket'
  AND left(storage_path, length(:'prefix')) = :'prefix'
  AND legacy_channel_fallback
  AND metadata_json @> '{"reconciled": true}'::jsonb
  AND NOT coalesce(metadata_json @> '{"scannerAccepted": true}'::jsonb, false)
ORDER BY id;
```

查询结果只是候选，不表示文件已触发、清单一定缺失或记录可以修改。上述目录查询也不涵盖目录外的权威清单路径，应结合绑定模板单独核对。对候选逐项核对实际对象、权威清单成员和业务日，以及相关在途 WAIT、到达组和业务处理结果；与业务方确认处理方式后再恢复该范围。未能确认时保持相关处理暂停，按既有运维流程处理，不直接批量删除文件记录或改写兼容标志。归档记录如需恢复，也须结合归档及恢复流程核查；上述查询只覆盖热表。

扫描器的 `arrival.enabled` 控制文件登记和后续清单回填是否写入到达组治理元数据；关闭时仍可保存清单中的束映射，但不会新增到达组元数据，也不会登记 manifest-only 导出触发记录。该开关不会清除先前已写入的文件组元数据，已有到达组仍可能由 Orchestrator 的治理调度处理。

无输入导出束使用 `EXPORT_TRIGGER` 文件类别；V380 扩展文件热表与归档表约束，V381 在独立迁移事务中验证。V380 的结构变更须短暂获取两表的 ACCESS EXCLUSIVE 锁，可能等待现有事务；部署时应结合目标库负载安排迁移。回退到只接受旧类别的约束前，须先停止产生该类记录，并妥善处理热表和归档表中的全部 `EXPORT_TRIGGER` 记录，否则旧约束无法恢复。

旧部署配置中的 `manifest-bindings` 和 `channel-bindings` 须先迁入共享声明，再从 Spring JSON、YAML 和 Helm 配置中移除；列表非空时 scanner 明确报错，不会将其当作第二事实来源或回退。其他 scanner 配置（例如前缀、清单开关、稳定窗口）仍由部署配置提供。若绑定与原静态配置完全一致，规则摘要保持一致；如果绑定或其他语义规则发生变化，按上文维护切换，不能直接重置进度。该变更不改变 Console API 或 SDK 协议。

## 指定渠道的文件等待

指定 `channelCode` 的 FILE_ARRIVAL 等待按 `file_record.arrival_channel_code` 和租户精确匹配，不从对象名或任务载荷的 `metadata.channelCode` 推断可信身份。托管上传会检查该渠道是否属于当前租户且已启用；未通过检查的上传仍按原接口受理，但不会获得可信渠道身份，也不能命中指定渠道的等待。未填写渠道的通用等待保持原有语义。

扫描器按共享声明中已解析的租户和对象完整目录绑定渠道；声明方法见[数据库脚本](../../scripts/db/README.md#入站范围归属声明)。目录须位于 scanner 前缀内并以 `/` 结尾；同一租户和目录不能重复，多个目录匹配时采用最长前缀。未配置或未匹配时仍登记文件并记录警告，但不会为新记录虚构渠道身份。应确认目录写入权限和租户路径归属与声明一致；更改绑定后新登记文件使用新配置，已登记记录不自动改写。

V378 在同一数据库迁移事务中区分迁移前已有记录和之后新增记录，不依赖文件业务日期或 `created_at`。只有迁移前缺少可信身份、且渠道编码能映射为 `SFTP`、`API` 或 `UPLOAD` 来源类型的记录允许按对应来源回退；无法映射的渠道只匹配精确渠道编码，不匹配无渠道的历史文件。命中历史回退时产生日志警告和节点输出 `legacyChannelFallback=true`。历史回退无法区分同来源类型的不同渠道，需结合实际文件核实。已在运行中的 WAIT 虽保留原节点参数快照，升级后的下一次探测也使用新匹配规则；升级前应核对在途等待与新文件渠道绑定。旧 Worker 在迁移后新增的无身份记录也不会回退；混合版本期间这些文件可能无法满足指定渠道的等待。旧 Orchestrator 仍执行原有前缀映射，因此应先完成全部 Orchestrator 实例升级，再依赖精确渠道语义。文件热表和归档镜像均增加身份字段；部署迁移需要获取表锁，应在目标数据量下评估等待及升级顺序。旧版归档器不能复制新字段，混合版本期间归档历史文件再恢复可能丢失回退资格；应先完成归档器升级，再依赖身份字段，目标环境的升级和恢复尚需验证。

## 开关

| 键 | 默认 | 说明 |
|---|---|---|
| `batch.worker.import.scanner.event-arrival.enabled` | `false` | 开启事件驱动端点。关闭时端点存在但只回 `triggered=false`,行为等价历史纯轮询 |
| `batch.worker.import.scanner.enabled` | `true` | 扫描总开关。关闭时通知返回 `triggered=false`、`reason=scanner-disabled`，不调用扫描器 |

对应环境变量:

```bash
BATCH_WORKER_IMPORT_SCANNER_EVENT_ARRIVAL_ENABLED=true
```

Compose / Helm 均已显式透传该变量；Helm values 入口为:

```yaml
orchestrator:
  importScanner:
    eventArrivalEnabled: true
```

## 指标

- `batch.import.event_arrival.notifications` — 事件开关开启时收到的通知数，包含扫描总开关关闭及在途合并的通知。
- `batch.import.event_arrival.scans` — 即时扫描取得租约并启动的次数，不代表完整周期完成或对象成功登记。

当前仅有上述两个计数器,尚无即时/轮询扫描的成功与失败、最近成功时间、登记结果或对象到达至 `file_record` 的延迟指标,也没有对应的 Prometheus 告警。通知撞上在途扫描时会合并并返回 `triggered=false`；对象可能在当前枚举游标之后或之前落地，仍需后续批次或周期发现，当前没有合并数量或事件到登记延迟指标。剩余指标和告警验收跟踪于 [TODO Master OBS-ENV-05](../plans/todo-master.md#事件与可用性监控目标环境验收2026-10-26)。

## 本地验证

1. 启动 import worker 时打开开关:

```bash
BATCH_WORKER_IMPORT_SCANNER_EVENT_ARRIVAL_ENABLED=true ./scripts/docker/up-apps.sh worker-import
```

2. 上传数据文件或 sidecar manifest 到 scanner 前缀后,手工模拟对象存储通知:

```bash
curl -fsS -X POST 'http://localhost:18083/internal/import/events/object-arrival' \
  -H "Content-Type: application/json" \
  -H "X-Internal-Secret: ${BATCH_INTERNAL_SECRET}" \
  -d '{"tenantId":"t1","bucket":"batch-dev","objectKey":"ingress/import-20260620-orders.csv"}'
```

3. 预期返回:

```json
{"code":"SUCCESS","data":{"triggered":true}}
```

4. 按实际扫描周期和稳定窗口等待，验证 `file_record` 已登记，并观察指标 `batch.import.event_arrival.notifications` /
`batch.import.event_arrival.scans` 增长；通知响应成功不代表文件已经登记。

## MinIO webhook 接入示例

本地 MinIO 可通过 bucket notification 调用一个内部网关或轻量转发器,由转发器补
`X-Internal-Secret` 后转发到 import worker。不要把 `BATCH_INTERNAL_SECRET` 直接暴露给外部对象存储控制面。

示例流程:

```bash
mc alias set batch-local http://localhost:19000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"

# Webhook endpoint 建议指向内部转发器,由转发器补 X-Internal-Secret。
mc admin config set batch-local notify_webhook:import_arrival \
  endpoint="http://<internal-gateway>/internal/minio/import-arrival" \
  queue_limit="10000"

mc admin service restart batch-local

mc event add batch-local/batch-dev arn:minio:sqs::import_arrival:webhook \
  --event put --prefix ingress/
```

转发器只需要把 MinIO 事件中的 bucket/object key 转成平台通知体,再调用
`/internal/import/events/object-arrival`。如果没有转发器,也可以让上游推送网关直接按本文接口调用。

## 边界 / 后续

- **v1 不做**:通知体里 object-key → tenant/job/bizDate 的精确路由、按 key 定向扫描(单对象)。这些是 v2 增强项。
- **v1 已做**:内部共享密钥鉴权。生产开启事件到达时必须配置非默认 `BATCH_INTERNAL_SECRET`,并只允许对象存储事件源或内部转发器访问 import worker 的 `/internal/**`。
- 如果事件到达 QPS 很高,当前 in-flight 合并会把并发通知折叠为一次扫描调用；扫描期间新到通知不会排队补扫，后续仍依赖分页周期及轮询推进。需要严格低延迟时再评估按 key 定向发现或合并队列。
- 与 Phase 4.4(`TriggerType.EVENT`)关系：显式投递到单个 Workflow 时仍使用 `POST /api/triggers/launch`，请求需提供 `externalEventCode`、`externalEventSource`、`externalEventId`、`workflowDefinitionId` 和 `triggerType=EVENT`。需要按事件规则自动投递到多个 Workflow 时，使用 `POST /api/triggers/events`，提供 `tenantId`、`externalEventCode`、`externalEventId`、`bizDate`，以及可选的 `params`、`dataIntervalStart`、`dataIntervalEnd`、`dryRun`；不要传 `workflowDefinitionId` 或 `externalEventSource`，目标集合由已启用的事件预期定义解析，来源从 API Key 读取。首个接纳会冻结目标集合和来源 `bizDate`，同一来源事件重投不会随规则变化扩展/缩减目标；同一事件身份带不同 payload 会冲突。两个入口均使用 `Idempotency-Key`，自动路由可通过 `GET /api/triggers/events/status?tenantId=...` 携带同一幂等键查询聚合状态。每个目标返回 TriggerRequest 状态与其 `workflowRunId/workflowRunStatus`；根 Workflow 完成状态不能从 TriggerRequest 的旧 JobInstance 关联字段推断。

### Trigger 外部事件来源凭据

外部系统调用 `POST /api/triggers/launch` 或 `POST /api/triggers/events` 时，必须同时设置 `X-Batch-Api-Key` 与 `X-Batch-Tenant-Id`。API Key 的 `key_name` 是事件来源；单目标 `/launch` 请求体中的 `externalEventSource` 必须与 `key_name` 完全一致，且 `triggerType=EVENT`。多目标 `/events` 不接受请求体指定来源或目标，服务端使用凭据来源并解析启用的事件预期目标。两种入口的 API Key 都必须显式包含 `trigger.event` scope，通配 scope `*` 不授予 EVENT 来源权限，`tenantId` 也必须与 API Key 所属租户一致。不匹配时返回 403，且不得写入 TriggerRequest、Outbox 或事件投递记录。

同一凭据只可调用 EVENT 启动及来源隔离的状态查询：单目标使用 `GET /api/triggers/launch/status`，多目标使用 `GET /api/triggers/events/status`。状态查询以 API Key 绑定的来源和租户过滤，避免该来源读取其他来源的请求。其他 Trigger 管理操作仍使用平台内部 `X-Internal-Secret`。API Key 验证成功结果进程内缓存 1 分钟，因此跨服务吊销的最长生效窗口为 1 分钟；到期时间每次缓存命中仍会重新检查。

租户管理员创建 API Key 时，设置名称为稳定的 source 标识并显式填写 `trigger.event` scope。不要使用默认通配 scope 作为事件来源授权。
