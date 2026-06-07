# 文件完整性 sidecar manifest 实施计划

日期：2026-06-07

## 范围

本计划覆盖两条链路：

1. 文件接收到达组：从“文件出现”升级为“manifest + checksum 验证通过”。
2. dispatch worker：对 NAS / OSS / SFTP 出站文件生成 `.chk` sidecar manifest。

第一阶段不改数据库表结构，先复用现有字段和 `metadata_json`，降低上线风险。

## 当前代码事实

- `batch.file_record` 已有 `file_size_bytes`、`checksum_type`、`checksum_value`。
- `batch-worker-import` 已有 `DatasetRuleEvaluator` 做 checksum 规则校验。
- `ConsoleFileArrivalGroupMapper` 当前从 `file_record.metadata_json.fileGroupCode` 聚合到达组。
- `DispatchFileContentResolver` 已能从 LOCAL / MinIO/S3 打开文件流。
- `RemoteFilesystemDispatchSupport` 已支持 NAS / OSS。
- `SftpDispatchChannelAdapter` 已支持 SFTP。
- 当前 dispatch 成功结果只有 `evidenceRef`，没有 manifest 信息。

## Phase 1：出站 Dispatch Sidecar

目标：NAS / OSS / SFTP 出站生成 `.chk`，不改 DB schema。

改动点：

- 新增 `DispatchManifestSupport`：
  - 计算数据文件 SHA-256。
  - 统计实际输出字节数。
  - 生成 JSON manifest。
  - 统一读取 `dispatch_manifest_enabled` / `dispatch_manifest_suffix`。
- 扩展 `DispatchResult`：
  - `manifestRef`
  - `manifestChecksum`
  - `manifestSizeBytes`
  - 保留旧构造器，避免大面积改调用方。
- 改 `RemoteFilesystemDispatchSupport.dispatchNas`：
  - 先写临时文件。
  - copy 时计算 checksum。
  - move 到正式文件。
  - 写 `target.chk`。
- 改 `RemoteFilesystemDispatchSupport.dispatchOss`：
  - 上传对象后写 `objectName.chk`。
- 改 `SftpDispatchChannelAdapter`：
  - 先上传 `remote.tmp-{uuid}`。
  - rename 到正式路径。
  - 上传 `remote.chk`。
- 改 `DispatchInvocationSupport.propagateIdentifiers`：
  - 把 manifest 信息写入 context attributes。
- 改 `DeliverDispatchStep`：
  - `updateFileStatus(..., fileMetadata)` 中附带 manifest 信息。
- 改 `DispatchStepExecutionAdapter.buildSuccessResponse`：
  - node outputs 增加 manifest 信息。

测试：

- `RemoteFilesystemNasPathTest` 新增 NAS `.chk` 生成和 checksum 断言。
- SFTP 如已有 testcontainer/fixture，则补 `.chk` 断言；否则先加单元级 helper 测试。
- OSS 集成测试补对象 `objectName.chk` 存在和内容断言。
- `DeliverDispatchStepTest` 断言 manifest 信息进入 attributes / file metadata。

验收：

- NAS / OSS / SFTP 默认生成 `.chk`。
- `dispatch_manifest_enabled=false` 时不生成 `.chk`。
- `.chk` 的 `sizeBytes/checksumValue` 与目标数据一致。

## Phase 2：入站到达组完整性

目标：到达组只把校验通过的文件计为 verified，触发条件按 verified 判断。

改动点：

- 新增入站 manifest 解析组件，建议放在 orchestrator 文件治理或独立 file-arrival 包：
  - 解析 `.chk` JSON。
  - 校验 `fileName` 安全性。
  - 校验 size。
  - 校验 SHA-256。
  - 写回 `file_record.checksum_type/checksum_value/file_size_bytes`。
  - 写回 `metadata_json.integrityState`。
- 改 arrival group 聚合查询：
  - `verified_count`
  - `integrity_failed_count`
  - `unverified_count`
- 扩展响应：
  - `ConsoleFileArrivalGroupResponse`
  - `FileArrivalGroupEntity`
  - OpenAPI / 前端类型后续同步。
- 到达组触发逻辑：
  - `ALL_OF` 使用 verified required set。
  - `ANY_OF` 至少一个 verified。
  - integrity failed 不触发，进入告警或人工确认。

测试：

- manifest 缺失：不触发。
- checksum mismatch：`INTEGRITY_FAILED`。
- size mismatch：`INTEGRITY_FAILED`。
- manifest 业务字段不匹配：`INTEGRITY_FAILED`。
- required set 全部 verified 后触发。

验收：

- 到达组页面能看到 arrived / verified / failed / waiting。
- 半文件不能触发下游 job。
- 错批次 manifest 不能触发。

## Phase 3：持久化与查询优化

触发条件：上线后查询频繁、metadata_json 查询慢，或审计要求需要结构化字段。

可选迁移：

- `file_dispatch_record.manifest_ref`
- `file_dispatch_record.manifest_checksum`
- `file_dispatch_record.manifest_size_bytes`
- `file_record.integrity_status`
- `file_record.manifest_ref`

索引建议：

- `(tenant_id, integrity_status, created_at)`
- `(tenant_id, (metadata_json->>'fileGroupCode'), created_at)` 如继续保留 JSON 查询。

## 风险与回滚

风险：

- 目标方原来直接扫数据文件，新增 `.chk` 后需要约定消费顺序。
- NAS/SFTP rename 在部分远端文件系统上可能不是严格原子，需要集成测试确认。
- OSS 不能 rename，只能上传数据对象后再上传 `.chk`；目标方必须以 `.chk` 为消费信号。

回滚：

- 渠道配置 `dispatch_manifest_enabled=false` 可关闭出站 `.chk`。
- 入站 `arrival_integrity_required=false` 可临时回到旧到达语义。
- 不改表结构阶段回滚成本低。

## 推荐实施顺序

1. 先做 Phase 1 出站 dispatch sidecar，风险最低，收益明确。
2. 再做 Console 到达组 verified/failed 统计，只改查询和响应。
3. 最后接入真正的入站 manifest 扫描/校验/触发逻辑。
4. 观察一轮本地 E2E 和 staging 后，再决定是否扩表。

