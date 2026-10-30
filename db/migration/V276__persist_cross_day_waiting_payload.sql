-- 跨日依赖处于等待状态时，保留完整且准确的启动上下文。
-- 应用保存的是加密信封；数据库列中不会存放明文。

alter table batch.workflow_node_run
    add column source_payload_protected jsonb;

alter table archive.workflow_node_run_archive
    add column source_payload_protected jsonb;

comment on column batch.workflow_node_run.source_payload_protected is
    '经 KMS 保护的源载荷快照，用于恢复跨业务日依赖等待。';
comment on column archive.workflow_node_run_archive.source_payload_protected is
    '经 KMS 保护的源载荷快照，用于恢复跨业务日依赖等待。';
