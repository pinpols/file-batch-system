-- Trigger Outbox 的 PUBLISHING 状态可经超时回收再次出现；状态本身不能标识领取轮次。
-- 仅面向空库基线：每次领取生成 UUID，旧投递者的 ACK/失败回写必须匹配该轮领取。
ALTER TABLE batch.trigger_outbox_event
    ADD COLUMN publish_claim_token UUID;

ALTER TABLE archive.trigger_outbox_event_archive
    ADD COLUMN publish_claim_token UUID;

ALTER TABLE batch.trigger_outbox_event
    ADD CONSTRAINT ck_trigger_outbox_claim_token
    CHECK ((publish_status = 'PUBLISHING') = (publish_claim_token IS NOT NULL));

ALTER TABLE archive.trigger_outbox_event_archive
    ADD CONSTRAINT ck_trigger_outbox_archive_claim_token
    CHECK ((publish_status = 'PUBLISHING') = (publish_claim_token IS NOT NULL));

COMMENT ON COLUMN batch.trigger_outbox_event.publish_claim_token IS
    '当前 PUBLISHING 领取轮次的 UUID；成功、失败回写须匹配，离开 PUBLISHING 时清空。';
