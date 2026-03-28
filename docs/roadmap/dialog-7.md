# dialog-7
配置 Excel 导入 / 导出维护

本文档用于把适合 Excel 维护的配置项拆成一轮可执行脚本，目标是把“页面表单维护”扩展为“可导出、可编辑、可回灌、可校验”的配置运维能力。

## 当前基线

- `batch-console-api` 已具备 JWT、角色、tenant、统一响应和 OpenAPI 契约
- 文件导入 / 导出链路已经支持 Excel，但控制台配置维护仍主要是页面表单
- 适合 Excel 的配置要做白名单字段控制，避免把运行态记录、审计记录和密钥材料混进来
- Excel 能力统一按 `upload / preview / apply / export` 四步走，且必须落在独立适配层，controller 只负责 HTTP 边界，不直接处理工作簿细节
- 现有 POI 依赖不动，Fesod 只允许在 `batch-console-api` 的 web 适配层试点；如果出现类路径冲突，只对 Fesod 这条依赖做局部 exclusion，不改 worker 和 `batch-common` 的既有 Excel 依赖
- 每一轮完成后，必须同步更新 [docs/api/console-api-protocol.md](../api/console-api-protocol.md) 和 [docs/api/console-api.openapi.yaml](../api/console-api.openapi.yaml)

## 适合导入 / 导出的配置

- `file template config`
  - 字段多、规则多、人工维护成本高
  - 适合批量编辑、离线审查、导出模板再回灌
- `file channel config`
  - 通道名、类型、endpoint、auth、receipt policy、timeout 天然表格化
  - 适合 Excel 维护
- `workflow definition / node / edge`
  - 可以做，但要谨慎
  - 适合大批量初始化或迁移，不适合频繁日常编辑
- `alert routing / notification policy`
  - 如果后续进入控制台范围，适合 Excel 批量维护
- `job definition` 的安全字段
  - 可以导出和批量修改
  - 只开放名称、队列、workerGroup、调度表达式、超时、重试策略等安全字段
  - 不开放执行器实现类、内部类名等敏感字段

## 适合只导出，不建议导入

- `config release`
- `config change log`
- `secret version`
- `audit log`
- `scheduler snapshot/history`
- `worker registry`
- `outbox retry/delivery`

这些属于运行记录或审计记录，不是配置源，适合报表导出，不适合 Excel 回灌。

## 第1轮：配置边界和白名单字段

- 用户：先把哪些配置能 Excel 导入 / 导出、哪些只能导出定下来。
- 我：会补配置域边界、字段白名单、只读字段和禁导入字段清单。
- 验收：导入模板不会混入密钥、审计和运行态记录。
- 交付：同步回写 `console-api-protocol.md` 和 `console-api.openapi.yaml`
- 状态：未开始

## 第2轮：file template config 导入 / 导出

- 用户：先做最适合 Excel 维护的模板配置。
- 我：会补模板配置的导出、下载、上传、预览、校验和应用接口。
- 验收：模板配置可批量编辑、可回灌、可审查。
- 交付：同步回写 `console-api-protocol.md` 和 `console-api.openapi.yaml`
- 状态：未开始

## 第3轮：file channel config 导入 / 导出

- 用户：再做文件通道配置，通道和 endpoint 很适合表格维护。
- 我：会补通道配置的导出、下载、上传、预览、校验和应用接口。
- 验收：通道配置可按行编辑，不再只靠页面表单。
- 交付：同步回写 `console-api-protocol.md` 和 `console-api.openapi.yaml`
- 状态：未开始

## 第4轮：workflow definition / node / edge 导出

- 用户：把 workflow 相关配置做成可导出，必要时支持迁移导入。
- 我：会补 workflow definition / node / edge 的导出视图和受控导入入口。
- 验收：工作流配置能迁移，但导入需要严格校验关系完整性。
- 交付：同步回写 `console-api-protocol.md` 和 `console-api.openapi.yaml`
- 状态：未开始

## 第5轮：job definition 安全字段批量维护

- 用户：把作业定义里可安全修改的字段做成 Excel 维护。
- 我：会补 job definition 的导出和白名单字段批量修改。
- 验收：可以批量改名称、队列、workerGroup、调度表达式、超时、重试策略。
- 交付：同步回写 `console-api-protocol.md` 和 `console-api.openapi.yaml`
- 状态：未开始

## 第6轮：只导出配置和运行记录

- 用户：最后把审计、运行记录、快照类内容做成只导出。
- 我：会补 config release / change log / secret version / audit log / scheduler snapshot / worker registry / outbox retry / outbox delivery 的导出接口或导出报告。
- 验收：这些对象只做报表，不支持 Excel 导入。
- 交付：同步回写 `console-api-protocol.md` 和 `console-api.openapi.yaml`
- 状态：未开始
