-- =========================================================
-- V191 - Console AI 审计：记录每次调用的 token 用量
-- =========================================================
-- 成本可观测的租户维度落点：token 指标为低基数(type=prompt|completion)不带 tenant tag,
-- 每租户成本靠审计表事后聚合(SELECT tenant_id, SUM(prompt_tokens+completion_tokens) ...)。
-- 仅 APPROVED 成功调用有 token 用量；拒绝/降级路径为 NULL。

ALTER TABLE batch.console_ai_audit_log
    ADD COLUMN IF NOT EXISTS prompt_tokens     INTEGER,
    ADD COLUMN IF NOT EXISTS completion_tokens INTEGER;
