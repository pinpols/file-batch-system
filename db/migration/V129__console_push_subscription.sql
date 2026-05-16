-- ============================================================================
-- V129: console_push_subscription — PWA Web Push 订阅表
-- ----------------------------------------------------------------------------
-- 配合前端 src/composables/useWebPush.ts(2026-05-16)。
-- 用户在 iOS PWA / Android PWA 授权 push 后,前端把 PushSubscription 完整 JSON
-- 发到 POST /api/console/push/subscribe,后端持久化在此表;告警 / 审批触发时
-- ConsolePushSender 按 user_id / tenant_id 查全部 endpoint 推送。
--
-- 唯一键:同一 (tenant_id, username, endpoint) 只存 1 条,允许同一用户多设备多条
-- 索引:tenant_id + username 给推送侧批量查;endpoint 给 unsubscribe / 失效清理
--
-- 失效清理:web-push 返回 410 Gone / 404 → ConsolePushSender 应当 DELETE,
-- 单独定时任务也可扫 last_seen_at 过期(30 天未上报登录) 清掉,见 service.
-- ============================================================================

CREATE TABLE IF NOT EXISTS batch.console_push_subscription (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    username        VARCHAR(128) NOT NULL,
    endpoint        TEXT         NOT NULL,
    p256dh_key      TEXT         NOT NULL,   -- 浏览器加密公钥(base64-url)
    auth_secret     TEXT         NOT NULL,   -- 认证密钥(base64-url)
    user_agent      VARCHAR(512),            -- 创建时的 UA 便于排障
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_pushed_at  TIMESTAMPTZ,             -- 最近一次实际推送时间
    last_seen_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),  -- 用户最近一次主动登录/订阅刷新

    CONSTRAINT uq_console_push_subscription UNIQUE (tenant_id, username, endpoint)
);

-- 推送侧批量查
CREATE INDEX IF NOT EXISTS idx_console_push_sub_tenant_user
    ON batch.console_push_subscription (tenant_id, username);

-- unsubscribe / 410 失效清理
CREATE INDEX IF NOT EXISTS idx_console_push_sub_endpoint
    ON batch.console_push_subscription (endpoint);

COMMENT ON TABLE  batch.console_push_subscription
    IS 'PWA Web Push 订阅;前端 useWebPush.ts → POST /api/console/push/subscribe 写入,告警/审批触发时 ConsolePushSender 推送';
COMMENT ON COLUMN batch.console_push_subscription.endpoint
    IS '浏览器推送服务 URL — Apple/Google/Mozilla 不同;Apple 是 web.push.apple.com/...';
COMMENT ON COLUMN batch.console_push_subscription.p256dh_key
    IS 'ECDH P-256 公钥(base64-url),用于加密 payload;前端 PushSubscription.keys.p256dh';
COMMENT ON COLUMN batch.console_push_subscription.auth_secret
    IS '消息认证密钥(base64-url);前端 PushSubscription.keys.auth';
