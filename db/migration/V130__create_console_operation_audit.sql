-- 通用「用户操作审计」表
--
-- 设计目标(配合 Aspect + @AuditAction 切面落库):
--   1) 所有 console-api 的写操作(close/approve/terminate/republish/login/...)的同事务留痕
--   2) schema 是「自包含事件」,字段名跟 Kafka payload 一致 → 将来切 Kafka 只换 sink
--      不改字段
--   3) 索引覆盖最常用 4 个查询维度:租户时间线 / 某人操作 / 某动作分布 / traceId 关联
--   4) payload 用 JSONB 而不是 TEXT,允许后续按 JSON 字段过滤
--   5) event_version 为将来 schema 迁移留口(增字段 → 升 version → 新老消费者按 version 分支)
--
-- 业务侧自带审计的表(file_audit_log/console_ai_audit_log/trigger_runtime_state_local_audit/
-- batch_day_operation_audit)继续保留,这张表只补「通用控制台操作」这一层缺口。
CREATE TABLE IF NOT EXISTS batch.console_operation_audit (
    id               BIGSERIAL    PRIMARY KEY,
    -- Kafka partition key 候选:同一租户 + 同一聚合根的事件保证有序
    tenant_id        VARCHAR(64)  NOT NULL,
    -- 聚合根类型(alert / approval / job_instance / worker / outbox / auth / ...)
    aggregate_type   VARCHAR(64)  NOT NULL,
    -- 聚合根 ID,用字符串兼容数字 id 和 code 两种(workerCode / alertId / approvalNo)
    aggregate_id     VARCHAR(128) NOT NULL,
    -- 动作名,与前端埋点 data-track 一致(alert.close / approvals.approve / ...)
    action           VARCHAR(128) NOT NULL,
    -- 操作主体
    operator_id      VARCHAR(128),
    operator_role    VARCHAR(64),
    -- 操作结果
    result           VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS',
    error_code       VARCHAR(64),
    error_message    VARCHAR(1024),
    -- 入参快照(截断到 2KB,防止超大请求把审计表撑爆 + 隐式过滤敏感字段由调用方 ensureSafe)
    params           JSONB,
    -- 追踪
    trace_id         VARCHAR(128),
    request_id       VARCHAR(128),
    -- 来源
    ip_hash          VARCHAR(64),
    ua_hash          VARCHAR(64),
    -- schema 版本,Kafka 消费者按 version 分支处理新增字段
    event_version    INTEGER      NOT NULL DEFAULT 1,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_console_operation_audit_result CHECK (result IN ('SUCCESS', 'FAILED'))
);

-- 租户时间线(管理员看「某租户最近做了什么」)
CREATE INDEX IF NOT EXISTS idx_console_operation_audit_tenant_time
    ON batch.console_operation_audit (tenant_id, created_at DESC);

-- 某人操作记录(审计反查「这个账号点过什么」)
CREATE INDEX IF NOT EXISTS idx_console_operation_audit_operator_time
    ON batch.console_operation_audit (operator_id, created_at DESC)
    WHERE operator_id IS NOT NULL;

-- 某动作的全局分布(运营看「关告警今天发生了多少次」)
CREATE INDEX IF NOT EXISTS idx_console_operation_audit_action_time
    ON batch.console_operation_audit (action, created_at DESC);

-- traceId 关联(从 APM/Loki 反查 → 哪个 trace 触发了什么写操作)
CREATE INDEX IF NOT EXISTS idx_console_operation_audit_trace
    ON batch.console_operation_audit (trace_id)
    WHERE trace_id IS NOT NULL;

COMMENT ON TABLE batch.console_operation_audit IS
    '通用控制台用户操作审计(由 @AuditAction Aspect 同事务写入)';
