-- =====================================================================
-- V108: result_version 主模型（ADR-017）
-- =====================================================================
-- 背景:
--   重跑 (RerunRequest.resultPolicy ∈ {CREATE_NEW_VERSION, KEEP_BOTH,
--   MANUAL_CONFIRM_EFFECTIVE}) 已在 API 层暴露，但底层多版本结果模型
--   不存在；同一 (tenant, jobCode, bizDate) 多次 SUCCESS 后下游"用哪
--   份"靠 run_attempt 隐式约定，监管复盘场景没有审计闭环。
--
-- 决策（ADR-017 §决策）:
--   独立 result_version 表，与 job_instance 1:N 关联；
--   按 (tenant_id, business_key) 维度内多版本，以 partial unique index
--   保证同 business_key 至多 1 行 EFFECTIVE；
--   状态机 PENDING / EFFECTIVE / SUPERSEDED / ARCHIVED；
--   payload 三种存储方式 INLINE_JSON / EXTERNAL_REF / FILE_RECORD；
--   GC 由 retention scheduler 推进 SUPERSEDED → ARCHIVED → 物理删除。
--
-- 字段:
--   business_key       业务主键，约定字符串拼接：
--                        job:{jobCode}:{bizDate}
--                        workflow:{workflowCode}:{bizDate}
--                        job:{jobCode}:{bizDate}:p={partitionKey}
--   version_no         同一 business_key 内单调递增
--   status             PENDING / EFFECTIVE / SUPERSEDED / ARCHIVED
--   effective_at       推到 EFFECTIVE 的时刻；NULL = 还未生效
--   deactivated_at     被新版本取代的时刻；NULL = 仍在线
--   payload_storage    INLINE_JSON / EXTERNAL_REF / FILE_RECORD
--   payload_json       INLINE_JSON 时直接落
--   payload_ref        EXTERNAL_REF 时放 oss://... ；FILE_RECORD 时放
--                        file_record:{id}
--   promotion_policy   AUTO_LATEST / MANUAL_APPROVAL
--   approval_id        MANUAL_APPROVAL 时关联 approval_request
--
-- 唯一约束:
--   uk_result_version_business_version: (tenant_id, business_key,
--     version_no) 防止重复版本号；
--   uk_result_version_effective: (tenant_id, business_key) WHERE
--     status='EFFECTIVE' partial unique index，保证 1 EFFECTIVE 不变量。
--
-- 索引:
--   按 (tenant_id, business_key, status) 走 status 过滤；
--   按 (tenant_id, job_instance_id) 反查；
--   按 (tenant_id, status, generated_at DESC) 给 console list 排序。
-- =====================================================================

CREATE TABLE IF NOT EXISTS batch.result_version (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    business_key        VARCHAR(256) NOT NULL,
    version_no          INTEGER      NOT NULL,
    job_instance_id     BIGINT       NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    effective_at        TIMESTAMPTZ,
    deactivated_at      TIMESTAMPTZ,
    payload_storage     VARCHAR(32)  NOT NULL DEFAULT 'INLINE_JSON',
    payload_json        JSONB,
    payload_ref         VARCHAR(512),
    generated_at        TIMESTAMPTZ  NOT NULL,
    generated_by        VARCHAR(128),
    promotion_policy    VARCHAR(32)  NOT NULL DEFAULT 'AUTO_LATEST',
    approval_id         BIGINT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE batch.result_version DROP CONSTRAINT IF EXISTS ck_result_version_status;
ALTER TABLE batch.result_version ADD CONSTRAINT ck_result_version_status
    CHECK (status IN ('PENDING', 'EFFECTIVE', 'SUPERSEDED', 'ARCHIVED'));

ALTER TABLE batch.result_version DROP CONSTRAINT IF EXISTS ck_result_version_payload_storage;
ALTER TABLE batch.result_version ADD CONSTRAINT ck_result_version_payload_storage
    CHECK (payload_storage IN ('INLINE_JSON', 'EXTERNAL_REF', 'FILE_RECORD'));

ALTER TABLE batch.result_version DROP CONSTRAINT IF EXISTS ck_result_version_promotion_policy;
ALTER TABLE batch.result_version ADD CONSTRAINT ck_result_version_promotion_policy
    CHECK (promotion_policy IN ('AUTO_LATEST', 'MANUAL_APPROVAL'));

CREATE UNIQUE INDEX IF NOT EXISTS uk_result_version_business_version
    ON batch.result_version (tenant_id, business_key, version_no);

CREATE UNIQUE INDEX IF NOT EXISTS uk_result_version_effective
    ON batch.result_version (tenant_id, business_key)
    WHERE status = 'EFFECTIVE';

CREATE INDEX IF NOT EXISTS idx_result_version_business_status
    ON batch.result_version (tenant_id, business_key, status);

CREATE INDEX IF NOT EXISTS idx_result_version_job_instance
    ON batch.result_version (tenant_id, job_instance_id);

CREATE INDEX IF NOT EXISTS idx_result_version_status_generated
    ON batch.result_version (tenant_id, status, generated_at DESC);

COMMENT ON TABLE batch.result_version IS
    '结果版本主模型 (ADR-017): 同 business_key 至多 1 EFFECTIVE; 重跑产生新版本';
COMMENT ON COLUMN batch.result_version.business_key IS
    '业务主键: job:{jobCode}:{bizDate} / workflow:{wfCode}:{bizDate} / job:{jobCode}:{bizDate}:p={partitionKey}';
COMMENT ON COLUMN batch.result_version.status IS
    'PENDING / EFFECTIVE / SUPERSEDED / ARCHIVED — 同 business_key 至多 1 EFFECTIVE';
COMMENT ON COLUMN batch.result_version.payload_storage IS
    'INLINE_JSON: payload_json 直接存; EXTERNAL_REF: payload_ref 是 oss://...; FILE_RECORD: payload_ref 是 file_record:{id}';
COMMENT ON COLUMN batch.result_version.promotion_policy IS
    'AUTO_LATEST: 立即 EFFECTIVE; MANUAL_APPROVAL: 创建 PENDING 等审批';

-- =====================================================================
-- archive 冷表镜像（按 §archive 冷表对齐红线）
-- =====================================================================
CREATE TABLE IF NOT EXISTS archive.result_version_archive
    (LIKE batch.result_version INCLUDING ALL);

COMMENT ON TABLE archive.result_version_archive IS
    'V108 archive mirror of batch.result_version; populated by retention scheduler when status=ARCHIVED';

-- =====================================================================
-- job_instance 加 replay_session_id（ADR-020 前置 + 通用 replay 标签）
-- 即使 ADR-020 未实施，本列也允许 RerunRequest 透传 replay 关联标签
-- =====================================================================
ALTER TABLE batch.job_instance
    ADD COLUMN IF NOT EXISTS replay_session_id BIGINT;

ALTER TABLE archive.job_instance_archive
    ADD COLUMN IF NOT EXISTS replay_session_id BIGINT;

COMMENT ON COLUMN batch.job_instance.replay_session_id IS
    'ADR-020 batch_day_replay_session.id 反查标签; NULL = 非 replay 创建';
COMMENT ON COLUMN archive.job_instance_archive.replay_session_id IS
    'V108 mirror column';

-- =====================================================================
-- 历史 SUCCESS 实例回填 v1 EFFECTIVE
-- =====================================================================
-- 策略:
--   每个 (tenant_id, job_code, biz_date) 三元组取**最新一次**
--   instance_status='SUCCESS' 的实例作为 v1 EFFECTIVE；
--   排序键: finished_at DESC NULLS LAST, created_at DESC, id DESC；
--   更早的 SUCCESS 实例不回填（避免 partial unique index 冲突 +
--   保持 migration 简洁）；后续如需历史多版本可单跑专用脚本。
--   payload_storage='INLINE_JSON'; result_summary 包成
--   {"legacy_summary": "..."}; 空 summary → '{}'::jsonb。
--   ON CONFLICT DO NOTHING — 允许多次跑 migration 幂等。
-- =====================================================================
WITH latest_success AS (
    SELECT
        ji.tenant_id,
        ji.job_code,
        ji.biz_date,
        ji.id                                                            AS job_instance_id,
        ji.finished_at,
        ji.created_at,
        ji.result_summary,
        ROW_NUMBER() OVER (
            PARTITION BY ji.tenant_id, ji.job_code, ji.biz_date
            ORDER BY ji.finished_at DESC NULLS LAST, ji.created_at DESC, ji.id DESC
        ) AS rn
    FROM batch.job_instance ji
    WHERE ji.instance_status = 'SUCCESS'
      AND ji.biz_date IS NOT NULL
      AND ji.job_code IS NOT NULL
)
INSERT INTO batch.result_version (
    tenant_id,
    business_key,
    version_no,
    job_instance_id,
    status,
    effective_at,
    payload_storage,
    payload_json,
    generated_at,
    generated_by,
    promotion_policy,
    created_at,
    updated_at
)
SELECT
    ls.tenant_id,
    'job:' || ls.job_code || ':' || ls.biz_date::text                    AS business_key,
    1                                                                     AS version_no,
    ls.job_instance_id,
    'EFFECTIVE'                                                           AS status,
    COALESCE(ls.finished_at, ls.created_at)                               AS effective_at,
    'INLINE_JSON'                                                         AS payload_storage,
    CASE
        -- PG length() 不接受 jsonb：原写法 length(ls.result_summary) 直接报
        -- "function length(jsonb) does not exist" 让 V108 整个 migration 失败。
        -- 用 IS NULL + 与空对象 / 空数组的 jsonb 比较替代（语义等价：空 jsonb → 用 {}）。
        WHEN ls.result_summary IS NULL
             OR ls.result_summary = '{}'::jsonb
             OR ls.result_summary = '[]'::jsonb
            THEN '{}'::jsonb
        ELSE jsonb_build_object('legacy_summary', ls.result_summary)
    END                                                                   AS payload_json,
    COALESCE(ls.finished_at, ls.created_at)                               AS generated_at,
    'V108_BACKFILL'                                                       AS generated_by,
    'AUTO_LATEST'                                                         AS promotion_policy,
    COALESCE(ls.finished_at, ls.created_at, CURRENT_TIMESTAMP)            AS created_at,
    COALESCE(ls.finished_at, ls.created_at, CURRENT_TIMESTAMP)            AS updated_at
FROM latest_success ls
WHERE ls.rn = 1
ON CONFLICT (tenant_id, business_key, version_no) DO NOTHING;
