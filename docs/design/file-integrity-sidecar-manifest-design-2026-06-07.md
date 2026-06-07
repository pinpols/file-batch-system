# 文件完整性 sidecar manifest 设计说明

日期：2026-06-07

## 背景

当前系统已经有文件级元数据能力：

- `batch.file_record` 已保存 `file_size_bytes`、`checksum_type`、`checksum_value`、`storage_type`、`storage_path`、`storage_bucket`。
- import worker 的 `DatasetRuleEvaluator` 已支持数据集级 `checksum_check`。
- Console 文件到达组查询通过 `file_record.metadata_json.fileGroupCode` 聚合展示到达状态。
- dispatch worker 的 NAS / OSS / SFTP 渠道目前负责把 `file_record` 引用的文件复制或上传到目标端，但没有统一生成出站 `.chk` / manifest，也没有统一目标端回算校验。

这意味着当前系统能记录 checksum，但在两个交接边界还不够硬：

1. 入站文件到达组可能把“文件名已出现”误判为“文件已完整可处理”。
2. 出站 dispatch 目标方可能读到半文件，或者缺少标准化的 size/checksum 对账依据。

## 目标

引入 sidecar manifest 机制，默认文件后缀为 `.chk`，内容使用 JSON，而不是传统单行 MD5。

目标能力：

- 入站：到达组按“manifest 存在 + checksum 校验通过”判断文件可处理。
- 出站：dispatch worker 对 NAS / OSS / SFTP 生成数据文件旁路 `.chk`，目标方以 `.chk` 作为安全消费信号。
- 兼容：保留现有 `file_record.checksum_type/checksum_value`，sidecar manifest 是交接协议补充，不替代数据库元数据。
- 可观测：Console 到达组展示 arrived / verified / failed / timeout 等状态，dispatch 输出暴露 manifest 路径和 checksum。

## Sidecar Manifest 协议

默认文件命名：

- 数据文件：`settlement_20260607.dat`
- 校验文件：`settlement_20260607.dat.chk`

`.chk` 内容为 JSON：

```json
{
  "schemaVersion": "file-sidecar-manifest-v1",
  "fileName": "settlement_20260607.dat",
  "sizeBytes": 12345678,
  "checksumType": "SHA-256",
  "checksumValue": "hex-string",
  "bizDate": "2026-06-07",
  "batchNo": "B20260607001",
  "recordCount": 100000,
  "tenantId": "default-tenant",
  "fileGroupCode": "settlement-daily",
  "generatedAt": "2026-06-07T12:00:00+08:00"
}
```

字段约束：

- `schemaVersion` 必填，便于未来扩展。
- `fileName` 必须是同目录数据文件名，禁止路径穿越。
- `sizeBytes` 必填。
- `checksumType` 默认 `SHA-256`，不建议继续引入 MD5。
- `checksumValue` 必填。
- `bizDate` / `batchNo` / `fileGroupCode` 用于业务组完整性判断。
- `recordCount` 可选，但有值时应参与数据集级校验。

## 入站到达组设计

### 当前现状

Console 到达组查询基于 `batch.file_record`：

- `metadata_json.fileGroupCode`
- `metadata_json.arrivalState`
- `created_at / updated_at`

它更像运行态聚合视图，不是严格的文件字节完整性判定。

### 目标状态机

建议到达组内单文件增加以下语义状态，落在 `file_record.metadata_json`，避免第一阶段扩表：

- `WAITING_ARRIVAL`：等待文件。
- `ARRIVED_UNVERIFIED`：数据文件存在，但未完成 manifest/checksum 校验。
- `VERIFIED`：manifest 存在且 size/checksum 校验通过。
- `INTEGRITY_FAILED`：manifest 缺失、size 不匹配、checksum 不匹配、业务字段不匹配。
- `TRIGGERED`：组条件满足并已触发下游。
- `TIMEOUT`：超过最晚容忍时间。

到达组触发条件应从“文件到达”升级为“文件已验证”：

- `ALL_OF`：required file set 全部 `VERIFIED` 才触发。
- `ANY_OF`：任一文件 `VERIFIED` 可触发，但 `INTEGRITY_FAILED` 需要告警。
- `QUORUM`：达到指定 verified 数量才触发。

### 入站读取规则

对文件落地区，建议统一规则：

1. 先看到数据文件，不立即触发。
2. 等待同名 `.chk`。
3. 读取 `.chk`，校验 JSON schema。
4. 校验 `fileName` 指向当前数据文件。
5. 校验 `sizeBytes`。
6. 计算数据文件 SHA-256，校验 `checksumValue`。
7. 校验 `bizDate` / `batchNo` / `fileGroupCode` 等业务字段。
8. 写入或更新 `file_record`：`checksum_type`、`checksum_value`、`file_size_bytes`、`metadata_json.integrityState=VERIFIED`。

失败路径：

- `.chk` 超时未到：`ARRIVED_UNVERIFIED`，到达组不触发。
- `.chk` 解析失败：`INTEGRITY_FAILED`。
- checksum 不匹配：`INTEGRITY_FAILED`。
- manifest 业务字段不匹配：`INTEGRITY_FAILED`。

## 出站 Dispatch 设计

### 当前现状

dispatch worker 的主要渠道：

- `NAS`：读取源文件流，`Files.copy` 到目标目录。
- `OSS`：读取源文件流，上传对象。
- `SFTP`：读取源文件流，`sftp.put` 到目标路径。
- `LOCAL` / stub：写 envelope。
- `API` / `API_PUSH` / `SMTP`：不是标准文件落地交接，不强制 sidecar 文件。

### 目标行为

对 NAS / OSS / SFTP 默认开启 sidecar manifest：

- 渠道配置 `dispatch_manifest_enabled=false` 可关闭。
- 渠道配置 `dispatch_manifest_suffix` 可覆盖默认 `.chk`。
- 生成 manifest 后，将 `manifestRef`、`manifestChecksum`、`manifestSizeBytes` 放入 `DispatchResult`，再写入 pipeline outputs / file metadata。

### 发布顺序

NAS：

1. 写临时数据文件：`target.tmp-{uuid}`。
2. 复制时同步计算 SHA-256 和字节数。
3. 原子 rename/move 到正式文件名。
4. 写 `target.chk`。
5. 返回 `evidenceRef` 和 `manifestRef`。

SFTP：

1. `put` 到远端临时文件：`target.tmp-{uuid}`。
2. 上传流同步计算 SHA-256 和字节数。
3. `rename` 为正式文件。
4. `put` manifest 到 `target.chk`。
5. 返回 `sftp://host/path` 和 `sftp://host/path.chk`。

OSS：

1. 读取源文件字节或流式上传时计算 SHA-256。
2. 上传数据对象。
3. 上传 `objectName.chk`。
4. 返回 `oss://bucket/objectName` 和 `oss://bucket/objectName.chk`。

API / SMTP：

- 不强制 `.chk` 文件。
- 可把 checksum 信息放进 JSON body、HTTP header 或邮件附件描述，作为后续增强。

## 配置建议

渠道级配置项：

| 配置键 | 默认值 | 说明 |
| --- | --- | --- |
| `dispatch_manifest_enabled` | `true` | 是否生成出站 sidecar manifest |
| `dispatch_manifest_suffix` | `.chk` | sidecar 文件后缀 |
| `dispatch_manifest_checksum_type` | `SHA-256` | 第一阶段只允许 SHA-256 |
| `dispatch_publish_temp_suffix` | `.tmp-{uuid}` | 临时文件后缀 |

模板或到达组配置项：

| 配置键 | 默认值 | 说明 |
| --- | --- | --- |
| `arrival_integrity_required` | `true` | 到达组是否必须等完整性校验通过 |
| `arrival_manifest_suffix` | `.chk` | 入站 sidecar 后缀 |
| `arrival_manifest_timeout_seconds` | 300 | 数据文件出现后等待 manifest 的时间 |

## 数据模型策略

第一阶段不扩表，复用现有字段：

- `file_record.file_size_bytes`
- `file_record.checksum_type`
- `file_record.checksum_value`
- `file_record.metadata_json.integrityState`
- `file_record.metadata_json.manifestRef`
- `file_record.metadata_json.manifestChecksum`
- `file_record.metadata_json.manifestSizeBytes`

第二阶段可评估扩展：

- `file_dispatch_record.manifest_ref`
- `file_dispatch_record.manifest_checksum`
- `file_dispatch_record.manifest_size_bytes`
- `file_record.integrity_status`

是否扩表取决于查询频率和索引需求。上线前第一阶段用 metadata 足够，风险小。

## 安全与一致性

- `.chk` 必须在数据文件发布后写入，目标方以 `.chk` 作为消费信号。
- 入站解析 `fileName` 禁止 `..`、`/`、`\`。
- checksum 使用 SHA-256。
- NAS/SFTP 使用临时文件发布，避免半文件被消费。
- manifest 不放密钥、token、凭据。
- manifest 中路径只写文件名或目标引用，不写敏感本地绝对路径，除非是本地运维场景。

## 验收标准

入站：

- 数据文件已到但 `.chk` 未到，不触发到达组。
- `.chk` checksum 不匹配，状态为 `INTEGRITY_FAILED`。
- `.chk` 校验通过，状态为 `VERIFIED`。
- 到达组 `ALL_OF` 只在 required set 全部 `VERIFIED` 后触发。

出站：

- NAS dispatch 生成数据文件和 `.chk`。
- SFTP dispatch 生成数据文件和 `.chk`。
- OSS dispatch 生成对象和 `.chk` 对象。
- `.chk` 中 size/checksum 与实际目标数据一致。
- 关闭 `dispatch_manifest_enabled=false` 后不生成 `.chk`，原链路仍成功。

