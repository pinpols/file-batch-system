# Console 维护与服务降级完善方案

> 状态：后端共享维护状态、版本 CAS、失联写保护、降级来源 Header、指标和告警已落地；Orchestrator 可按实例排空 Trigger Launch 消费；配对前端写保护、维护页和降级提示已完成本地真实 HTTP/浏览器联测。双 Console 副本 staging 收敛、Orchestrator 多实例排空演练、告警阈值校准及真实移动设备验收仍待完成。复核日期：2026-10-12。

## 1. 目标与边界

本方案只完善 BFS 已有的两项能力：

1. Console 维护窗口：全冻结、只读、公告、恢复和审计。
2. Console 调用 Trigger / Orchestrator 等下游控制面服务时的可控降级。

系统定位仍是“批量运行控制面 + 文件/任务交付闭环”。本方案不新增通用工作流编排、服务网格、配置中心、数据治理平台或独立告警产品。

## 2. 当前实现盘点

| 能力 | 当前状态 | 说明 |
|---|---|---|
| 全冻结 / 只读维护 | 已有 | `MaintenanceModeFilter` 返回 503；只读模式允许 GET；全冻结时管理员普通写操作也拦截，仅保留诊断读取 |
| 公共维护状态 | 已有 | `GET /api/console/system/maintenance`，前端轮询 |
| 管理员热切换 | 已有 | `PUT /api/console/admin/system/maintenance`，带审计 |
| 前端公告和维护页 | 已完成 | 桌面端、移动端均挂载；状态 stale/同步失败、维护导航和恢复回跳有测试 |
| 下游读路径降级 | 已有 | `DownstreamFallback` + Resilience4j |
| 下游写路径 fail-fast | 已有 | 防止控制面故障时误报成功 |
| 多副本状态一致性 | 已落后端 | V215 单例表为事实源；Console 副本每 5 秒轮询并按 version 收敛 |
| 服务范围降级 | 通知范围已实现，路由不扩展 | `affectedServices` 展示公告并识别固定应用通知范围，不执行服务启停或业务路由 |
| 前端统一冻结写操作 | 已完成 | 共享写守卫和请求拦截器覆盖维护期间写操作，后端 503 仍为最终防线 |
| 降级来源前后端契约 | 已联测 | `X-Degraded-Source` 仅在真实 fallback 响应中输出；前端 banner 支持聚合、过期和清除 |
| 维护指标和告警 | 已落后端 | enabled/read-only/shared-state/version gauge、维护请求结果、fallback 计数和副本 lag 告警已接入；阈值仍需真实流量校准 |
| Orchestrator 实例排空 | 已实现 | 只暂停当前实例的 Trigger Launch listener；在途业务允许完成，新进入 listener 的消息通过 `nack` 延迟重投；多实例滚动维护仍需 staging 验收 |

## 3. 设计原则

### 3.1 维护模式优先保护数据正确性

- 全冻结时，除健康检查、登录态检查、登出、维护状态和维护控制接口外，业务请求返回 503。
- 只读时，仅允许明确的读路径通过；所有写路径仍由后端统一拦截。
- 管理员绕过必须是显式、可审计、可撤销的运维能力，不把普通管理员默认为“无限绕过”。
- 前端禁用按钮只是用户体验优化，不能替代后端拦截。

### 3.2 降级只用于可证明安全的读路径

- 列表、状态、诊断摘要等允许使用空集合、UNKNOWN 或短时 stale 数据时才降级。
- 创建、修改、暂停、恢复、重试、取消、重放、审批和文件操作全部 fail-fast。
- 降级响应必须明确来源，不能让前端把空数据误显示为真实空结果。

### 3.3 先解决多副本一致性，再扩展范围

不在本阶段做任意服务的动态路由平台。维护状态先选一个已有基础设施作为共享事实来源，并保留版本号、更新时间和实例确认信息，确保所有 Console 实例收敛到同一状态。

## 4. 后端完善计划

### P0：维护状态共享与契约收口

#### P0.1 共享维护状态

当前已将单实例 `AtomicReference` 改为 V215 PostgreSQL 控制面单例表，作为维护状态唯一事实来源。维护开关是低频写、高一致性要求的控制面状态，不把 Redis 作为权威来源，也不新增配置中心。

状态至少包含：

```text
enabled
readOnly
message
etaAt
affectedServices       # 公告展示及固定应用通知范围，不控制路由
version
updatedAt
updatedBy
```

要求：

- 更新使用版本 CAS，防止旧管理员页面覆盖新状态。
- PostgreSQL 记录同时承担状态版本、审计关联和回滚依据；Redis 如需使用，只能作为非权威读取缓存。
- 所有 Console 实例按短周期读取或订阅变更并确认版本。
- 读取共享状态失败时按安全策略处理：维护状态不可确认时不放行写请求。
- 启动时加载共享状态；没有共享记录时才使用启动配置作为初始值。
- 明确回滚：共享状态恢复为 `enabled=false`，再验证所有实例已确认。

#### P0.2 统一维护响应

维护 503 响应固定包含：

```json
{
  "maintenance": true,
  "readOnly": false,
  "message": "...",
  "etaAt": "...",
  "affectedServices": [],
  "version": 12
}
```

固定响应头：

- `X-Maintenance: blocked|read-only|admin-bypass`
- `Retry-After`
- `X-Maintenance-Version`

OpenAPI、错误码说明和前端生成类型以该契约为准。

#### P0.3 收口管理员绕过

- 保留维护控制、状态、健康检查和登出白名单。
- 已收敛：`ROLE_ADMIN` 不再绕过全冻结期间的普通写请求；只读诊断保留 `X-Maintenance: admin-bypass`，维护控制端点单独走白名单和原有审计。
- 若未来需要维护期间执行普通业务写操作，必须新增独立权限、显式授权和逐请求审计；当前不提供该 break-glass 能力。

### P1：下游降级和前端提示闭环（实现已完成，环境验收剩余）

#### P1.1 标准化降级来源（后端已落地）

`DownstreamFallback` 在实际返回 fallback 时统一向当前 Web 响应写入：

```text
X-Degraded-Source: trigger,scheduler-status
```

要求：

- 只在确实使用 fallback 时输出。
- 多个下游来源合并、去重，不暴露内部异常堆栈。
- 正常响应不携带该头。
- 写路径不返回“降级成功”，仍返回明确错误。

#### P1.2 统一下游调用策略

- 所有 Console `RestClient` ProxyService 使用 `DownstreamFallback`。
- 增加 ArchUnit 或静态守护，禁止散落的 `RestClientException` 降级。
- 每个读端点登记 fallback 语义：空集合、UNKNOWN 或 stale cache，禁止默认吞错。
- 保持现有 Resilience4j 配置，不扩展成服务网格或通用路由系统。

#### P1.3 最小观测闭环（后端已落地）

补充已有 Micrometer 指标：

```text
console.maintenance.active{read_only}
console.maintenance.requests{result}
downstream.call.total{service,op,outcome}
downstream.call.duration{service,op}
```

只增加现有观测体系中的指标和告警规则：

- 维护状态启用时间过长。
- 维护 503 突增。
- 某下游 fallback 比例持续超过阈值。
- 写请求被降级错误接受，必须为零。

## 5. 前端完善计划

### P0：用户状态和写保护一致

#### P0.1 维护状态模型

`appStore` 继续作为前端展示状态单一入口，新增/确认：

- `version`
- `updatedAt`
- `lastSyncedAt`
- `syncError`
- `isStale`

轮询策略：

- 启动立即拉取。
- 正常状态 30 秒轮询。
- 维护期间 10 秒轮询，恢复后回到 30 秒。
- 请求失败不清空最近一次维护状态；超过阈值显示“状态同步异常”。

#### P0.2 统一写操作守卫

新增一个共享 composable / directive，仅负责 UI 层体验：

- 维护全冻结：所有业务写按钮禁用或隐藏。
- 只读维护：所有写按钮禁用，保留列表筛选、查看详情、导出等明确读操作。
- 禁用原因使用统一国际化文案。
- 后端 503 仍是最终防线，前端不能自行判断为成功。

优先覆盖：作业、工作流、批量日、重试/取消/暂停恢复、配置导入导出、告警处理和权限管理页面。

#### P0.3 503 即时导航

收到维护 503 后：

1. 解析维护响应并更新 store。
2. 保留当前路由作为 `redirect`，但不保留敏感 query。
3. 当前不在维护页时立即跳转 `/maintenance`。
4. 恢复后回到原页面；原页面恢复失败时回到安全首页。

### P1：降级提示和可用性

#### P1.1 降级来源展示

前端仅在收到 `X-Degraded-Source` 时展示降级提示：

- 顶部轻量 banner，不遮挡主要业务内容。
- 展示受影响的下游服务和“数据可能暂时不完整”的明确文案。
- 不把 fallback 空列表显示成“没有数据”。
- 60 秒 TTL，收到正常响应或过期后清除。

#### P1.2 维护页信息

维护页展示：

- 当前模式：全冻结 / 只读。
- 维护原因。
- 预计恢复时间及本地化时区。
- 最近一次状态同步时间。
- 手动重试按钮。
- 管理员入口仅按权限显示。

不在维护页增加通用工单、编排或故障平台功能。

## 6. 前后端联测与验收

### 合同测试

- OpenAPI schema 与前端生成类型一致。
- 维护 503 body、headers、`Retry-After`、version 一致。
- `X-Degraded-Source` 正常响应不出现，fallback 响应必出现。
- 中英文文案、ETA 时区和 affected service 名称一致。

### 后端测试

- Filter 全冻结、只读、白名单、无权限绕过。
- 共享状态 CAS、版本递增、重启恢复。
- 两个 Console 实例切换维护状态，最终版本一致。
- 下游读 fallback、写 fail-fast、断路器打开/半开/恢复。
- 审计记录完整且不泄露凭据。

### 前端测试

- 首次加载、轮询、维护开启、维护关闭。
- 全冻结和只读下所有重点写页面的按钮状态。
- 503 即时跳转、恢复后回跳。
- 降级来源 banner 的显示、合并、过期和清除。
- 桌面端和移动端布局、中文/英文、长公告和无 ETA。

### 验收门槛

1. 双实例状态收敛通过。
2. 维护期间没有普通用户写请求进入业务服务。
3. 没有将 fallback 空结果误报为真实空数据。
4. 维护开启/关闭和降级事件可在现有指标、日志、审计中追溯。
5. 所有新增契约有后端测试、前端测试和文档引用。

## 7. 分阶段交付

| 阶段 | 内容 | 结果 |
|---|---|---|
| P0-A | 共享状态、版本 CAS、实例确认 | 多副本维护状态一致 |
| P0-B | 维护响应契约、权限和审计收口 | 维护窗口行为可控、可追溯 |
| P0-C | 前端统一写保护和 503 即时导航 | 用户不会继续操作失效页面 |
| P1-A | `X-Degraded-Source` 和 Proxy 守护 | 降级状态前后端一致 |
| P1-B | 现有指标、告警和联测 | 能发现并验证降级 |

## 8. 明确不做

- 不做通用服务网格或自研流量编排。
- 不做任意业务服务的动态路由控制台。
- 不把维护公告扩展成工单或通知中心。
- 不对写接口做“自动成功”降级。
- 不引入新的消息总线、配置中心或独立监控平台。
- 不把 `affectedServices` 在没有真实路由语义前包装成“服务级维护”。

## 9. 当前状态口径

本文是“基础能力之上的完善方案”，不是从零实施说明。当前已实现能力包括维护拦截、全冻结/只读、公共状态接口、管理员热切换、503 body/header、Retry-After、审计、下游读 fallback / 写 fail-fast、统一 `X-Degraded-Source`、维护请求指标和告警；配对前端的统一写守卫、维护状态页面及降级 banner 已完成本地真实 HTTP 和浏览器验证，覆盖 Trigger 故障到恢复。仍待双 Console 副本 staging 收敛、不同下游服务及真实移动设备验收、告警阈值按真实流量校准。实际运行入口以 `docs/runbook/maintenance-mode.md` 和 `docs/runbook/downstream-degradation.md` 为准；本地联测不应写成 staging/生产证据。

## 2026-10-26 维护通知联动补齐

- **目标**：维护期间抑制明确范围的预期停机通知，保留告警计算、持久化、真实故障和恢复语义；提前结束或到期后恢复通知。
- **范围与授权**：仅在 `fix/wdi-current` 配对工作树实现代码、配置、文档与定向本地验证；不提交、不部署、不重启现有服务、不修改真实 Alertmanager、不发送真实渠道通知。本地 `stop-all.sh` 的 30 秒开发便利例外保持不变。
- **决定**：复用 Alertmanager 有限生命周期控制信号和 inhibit，不增加第二套通知编排或维护表。范围必须同时匹配部署标签和固定应用 job；未知范围保持告警。`affectedServices` 的识别编码新增通知用途，仍不承担服务启停和业务路由。
- **实现**：维护状态只在事务提交后更新本地缓存；`MaintenanceAlertWindow` 固定窗口与服务编码；`MaintenanceAlertPublisher` 同步、解除和失败指标；Alertmanager 空接收端/定向抑制；Docker/Helm 范围对齐；统一 HTTP Observation 标签和错误率排除；Alertmanager 启动入口改为校验挂载 Secret 并直接加载模板，保留字符集及最小长度约束；同步前端中英文说明。原业务告警与送达回执不被此路径修改。
- **验证结果**：最终受影响 Java 9 类 **52/52** 通过（`/private/tmp/wdi-maintenance-final-java.log`，2026-10-26 12:51 +08:00）；包括真实 Prometheus Registry 中计划 503、真实 500、正常请求的同指标族共存，以及事务提交/回滚时机。此前红灯复现计划 503 无标记、控制信号时间错误序列化和未提交状态泄漏，均已修正；新增 Registry 测试的资源关闭 API 编译错误也已修正。
- **规则与隔离验证**：`promtool test rules` 的 canonical fixture **21 场景**及 Helm 渲染后 **2 场景**通过；`amtool check-config` 通过。独立、无网络、空接收端 Alertmanager 实测同范围两类可用性告警被抑制，其他服务/部署/缺范围/数据完整性/监控告警不被抑制，提前结束与自然到期均解除。Secret 入口有效/空/过短/非法/缺文件 5 项和 container/local 两种 Prometheus 目标生成通过；所有临时容器已删除，未配置真实接收端。
- **部署与工程检查**：Helm 缺省/覆盖范围各核对 Console、8 个 ServiceMonitor、两类可用性查询；Compose 非空/空范围各核对 Console/Prometheus 透传，8 个服务继续保留 300 秒停止宽限。配置默认值、Helm env、环境变量与 env Shell 安全、观测契约、规则副本、脚本、文档、OpenAPI 431 路由和定向差异检查通过。前端 API 重新生成且 drift 检查、typecheck、6131 个 i18n key、75 份当前文档及 changelog 检查通过；changelog 工具比较范围为 `HEAD^...HEAD`，本轮根日志另行人工核对。
- **复现命令**：Java 使用 `./mvnw -ntp -pl batch-console-api -am -Dtest=MaintenanceAlertWindowTest,MaintenanceAlertPublisherTest,MaintenanceHttpObservationConventionTest,MaintenanceModeFilterTest,MaintenanceStateHolderTest,MaintenanceStateHolderTransactionTest,ConsoleAdminMaintenanceControllerTest,AlertmanagerNotifyServiceTest,AlertmanagerNotifyControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`。原生工具使用本机缓存的 `prom/prometheus:v3.11.3` 和 `prom/alertmanager:v0.28.1`，以 `--network none` 和只读挂载运行 `promtool test rules` / `amtool check-config`；部署范围和运行抑制脚本留在 `/private/tmp/wdi-maintenance-helm-check.py`、`wdi-maintenance-alertmanager-check.py`、`wdi-maintenance-entrypoint-check.py`，均只使用合成场景。
- **尚未验证**：站点实际配置加载、多副本并发更新、真实滚动停机和最终收件；不继承历史环境 PASS。配置和语义说明以[维护 SOP](../runbook/maintenance-mode.md#10-维护期通知抑制2026-10-26)为准。
