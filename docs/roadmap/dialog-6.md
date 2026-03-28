# dialog-6
控制台接口补全

本文档用于把控制台前端所需的后端接口补全拆成 6 轮推进，按“先契约、再核心页面、再运维动作、最后联调收口”的顺序执行。

## 当前基线

- `batch-console-api` 已有一批可用接口，但协议文档还偏简版
- 控制台权限模型已经存在，后端以 `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_CONFIG_ADMIN` 为主
- 前端工程尚未实施，当前仍是后端控制台接口先行
- 需要优先补稳定 DTO、接口目录、权限矩阵和测试覆盖
- 每一轮完成后，必须同步更新 [docs/api/console-api-protocol.md](../api/console-api-protocol.md) 和 [docs/api/console-api.openapi.yaml](../api/console-api.openapi.yaml)

## 第1轮：统一接口契约

- 用户：先把控制台接口契约统一，避免前端对接时每个页面都临时猜字段。
- 我：会补 `CommonResponse`、分页结构、错误码、请求头约定、tenant / operator / idempotency 规则，并把现有 `docs/api/console-api-protocol.md` 扩成可落地协议。
- 验收：前端能按统一协议接入；写接口、查接口、错误返回都有稳定口径。
- 交付：同步回写 `console-api-protocol.md` 和 `console-api.openapi.yaml`
- 状态：已完成

## 第2轮：首页 / 运维总览

- 用户：先补首页和运维总览，前端第一屏要能看到系统状态。
- 我：会补 `ops/summary` 的稳定 DTO，覆盖作业数、运行中、失败、告警、worker 状态、outbox 积压和 SLA 概况。
- 验收：控制台首页不再拼散接口，能直接拿到总览数据。
- 交付：同步回写 `console-api-protocol.md` 和 `console-api.openapi.yaml`
- 状态：已完成

## 第3轮：审批流 + 配置发布与回滚

- 用户：先补审批流和配置发布，控制台里最常用的运维动作要先稳定。
- 我：会补审批列表 / 详情 / approve / reject / batch approve，配置发布 / 灰度 / 回滚 / secret rotate / change log 的请求响应 DTO 和列表 DTO。
- 验收：审批和配置页面有完整闭环，接口不再直接暴露实体。
- 交付：同步回写 `console-api-protocol.md` 和 `console-api.openapi.yaml`
- 状态：已完成（2026-03-28）

## 第4轮：Worker 和文件治理

- 用户：先补 Worker 排空、强制下线、认领任务，以及文件治理动作。
- 我：会补 drain / force-offline / claimed-tasks 的统一 DTO，文件 archive / delete / redispatch / presign-download / arrival-group action 的请求响应 DTO。
- 验收：运维页和文件治理页可直接对接，接口语义清晰。
- 交付：同步回写 `console-api-protocol.md` 和 `console-api.openapi.yaml`
- 状态：已完成（2026-03-28）

## 第5轮：调度快照 + AI 聊天 + 全量查询入口

- 用户：先补调度快照、AI 聊天和全量查询页，补齐控制台主要信息面。
- 我：会补 scheduler snapshot / history 的 DTO，AI chat request / response，查询列表统一的分页 DTO 和详情 DTO。
- 验收：查询页、调度页、AI 页都能按统一契约接入。
- 交付：同步回写 `console-api-protocol.md` 和 `console-api.openapi.yaml`
- 状态：已完成（2026-03-28）

## 第6轮：联调收口

- 用户：最后补联调、权限矩阵和测试覆盖，确保前端真能用。
- 我：会补路由级权限矩阵、接口集成测试、协议文档回写和必要的前端联调样例。
- 验收：前端按角色接入不报 403 误用，主要页面接口全部闭环。
- 交付：同步回写 `console-api-protocol.md` 和 `console-api.openapi.yaml`
- 状态：未开始
