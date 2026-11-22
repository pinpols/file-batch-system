-- V389：告警升级通知 Outbox 的领取凭据。
--
-- 旧回写只校验 publish_status='PUBLISHING'：发布者停顿超过超时时间后，另一实例可回收并重新领取，
-- 旧发布者随后的成功/失败/放弃仍会命中新一轮的 PUBLISHING 行。
--
-- 领取时写入本轮 claim_token，状态复位与所有发布结果回写都必须匹配该凭据。
-- 列可空以兼容升级前遗留的 PUBLISHING 行：其 claim_token 为 NULL，超时复位仍可回收。
-- 混版期间旧进程仍使用不含凭据的旧 SQL，完整保护从旧发布器全部退出后生效。

alter table batch.alert_escalation_notification_outbox
    add column claim_token uuid;

comment on column batch.alert_escalation_notification_outbox.claim_token is
    '本轮发布者凭据；领取时生成，状态复位与成功/失败/放弃回写必须匹配。';
