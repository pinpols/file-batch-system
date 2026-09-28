# 运维操作与控制台入口

## 运维通过控制台/代理做,不直接改状态库
- console-api 对 `outbox_event` 等只读;清理/重投走 `ConsoleOrchestratorPort` 的默认实现 `DefaultConsoleOrchestratorProxyService` → orchestrator `/internal/outbox/*`。
- 状态变更最终都由 orchestrator(唯一状态主机)写入数据库。

## 常用查询(console-api 只读)
- 查 job 实例、执行日志、失败分类(failure class):用控制台对应列表/详情页。
- AI 审计记录:`/api/console/queries/ai-audits`(只记哈希 + 512 字预览,不存原文)。

## 常用运维脚本(scripts/ops 等)
- `heal-stuck-outbox.sh`:修卡住的 outbox。
- `heal-dead-letters.sh`:处理死信。
- `heal-zombie-pipelines.sh`:清理僵尸 pipeline。
- `heal-retry-tasks.sh` / `heal-retry-partitions.sh`:重试任务/分区。
- `inspect-db.sh` / `inspect-workers.sh` / `inspect-all.sh`:巡检。

## 重试 / 重跑
- 任务级重试有退避;实例可按配置快照重跑(rerun config snapshot),保证重跑用当时配置版本可追溯。

## 配置与开关
- 业务异常码、字典枚举、i18n 在 `batch-common`;暴露给前端的枚举需登记 `ConsoleMetaQueryService`。
- 安全 bypass 总开关 `batch.security.bypass-mode`(认证/加解密/审批全放行),**生产 profile 强制拒绝**。
- AI 知识库默认加载 `classpath:ai-knowledge/*.md`;工程治理、发布门禁或运维入口变化时,需要同步更新对应知识文档,否则 Console AI 会继续按旧语料回答。
- AI 聊天 provider 支持 `anthropic`、`openai` 和 `openai-compatible`。兼容模式用于 DeepSeek、千问、智谱、Kimi、MiniMax 或私有兼容代理,需显式配置 `BATCH_CONSOLE_AI_OPENAI_COMPATIBLE_PROVIDER_NAME`、`BATCH_CONSOLE_AI_OPENAI_COMPATIBLE_BASE_URL`、`BATCH_CONSOLE_AI_OPENAI_COMPATIBLE_API_KEY`、`BATCH_CONSOLE_AI_OPENAI_COMPATIBLE_CHAT_MODEL`。兼容模式不支持自动 failover,避免 prompt/context 未经授权转发到另一家服务。
- RAG embedding 与聊天 provider 分离,仍走 `spring.ai.openai.embedding` / `OPENAI_EMBED_MODEL`;不要为了接 DeepSeek/Kimi/千问等聊天端点把 `OPENAI_BASE_URL` 指过去,否则知识库向量化可能失效。
- RAG 默认不加载完整 `docs/`。如部署侧通过 `batch.console.ai.rag.locations` 追加外部文档,应只挂当前有效的 design/architecture/runbook/API 摘要,避免 archive/backlog/verifications 历史结论污染回答。
- 生产 Docker Compose overlay 要显式传入已验证的 `IMAGE_TAG`,不得依赖可变 latest。

## 时区与编码
- 全系统默认时区 `Asia/Shanghai`,业务代码禁用 `ZoneId.systemDefault()`,统一注入 `BatchTimezoneProvider`。
- 全系统 UTF-8;代码用 `StandardCharsets.UTF_8`。

## 插件与渠道运维
- Import/Export/Process/Dispatch 的插件只作用在固定步骤内部;不要把插件理解为可以替换整条 Worker 主链。
- Dispatch 渠道 adapter 按官方 `channel_type` 注册;同一渠道多个 adapter 同时支持会启动失败。生产排查 NAS/OSS/SFTP/EMAIL/API 渠道时,先确认实际 profile 下只有一个真实 adapter 生效,local/test stub 不应接管生产渠道。
- `make test-parallel` 默认拒绝执行,需 `ALLOW_PARALLEL_TESTS=1` 显式 opt-in,避免 Docker/CPU/内存竞争导致误判。

## Console AI 助手自身的使用边界
- 仅 ADMIN/AUDITOR 白名单可用,默认关闭(`batch.console.ai.enabled=false`)。
- 只回答 batch 平台相关问题;超范围、命中安全词(密钥/密码等)直接拒绝。
- 只给建议/草稿/流程,不直接代执行高风险操作,不改业务状态。
