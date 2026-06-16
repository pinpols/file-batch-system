# 运维操作与控制台入口

## 运维通过控制台/代理做,不直接改状态库
- console-api 对 `outbox_event` 等只读;清理/重投走 `ConsoleOrchestratorProxyService` → orchestrator `/internal/outbox/*`。
- 状态变更最终都由 orchestrator(唯一状态主机)落库。

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

## 时区与编码
- 全系统默认时区 `Asia/Shanghai`,业务代码禁用 `ZoneId.systemDefault()`,统一注入 `BatchTimezoneProvider`。
- 全系统 UTF-8;代码用 `StandardCharsets.UTF_8`。

## Console AI 助手自身的使用边界
- 仅 ADMIN/AUDITOR 白名单可用,默认关闭(`batch.console.ai.enabled=false`)。
- 只回答 batch 平台相关问题;超范围、命中安全词(密钥/密码等)直接拒绝。
- 只给建议/草稿/流程,不直接代执行高风险操作,不改业务状态。
