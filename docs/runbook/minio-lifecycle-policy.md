# MinIO 生命周期策略 runbook

> 针对 R-4.6（错误输出文件永驻 MinIO 无 TTL）的运维 runbook。
> 维护人：SRE；每半年复盘一次过期天数是否合理。

## 背景

`batch-worker-import` 在 validate / load 失败时会把坏行以 NDJSON 写入 MinIO
`batch-error-output` bucket（具体名见 `batch.minio.error-bucket`）。
设计文档 §9.11 约定 `errorOutputRetentionDays`，但 worker 层当前**未实现主动清理**。
为避免持续累积占用存储，统一使用 **MinIO bucket lifecycle rule** 在存储侧做自动过期，
零代码改动即可生效。

其他**导入/导出过程中的临时中间产物** bucket（预处理 spool 目录、parsed NDJSON、
validated NDJSON、export draft）只要命名固定，都适用同一套策略。

## 标准策略

| bucket | 内容类型 | 保留天数 | 理由 |
|---|---|---|---|
| `batch-error-output` | 坏行 NDJSON（可能含 PII） | **30 天** | 合规审计 + 重试上限；超过应自动脱敏清理 |
| `batch-import-staging` | 预处理 spool / parse 中间文件 | **7 天** | 仅用于断点重试；正常流程不留 |
| `batch-export-draft` | 导出生成中文件 | **3 天** | 正常流程会被 StoreStep 移走；留 3 天处理异常 |
| `batch-dispatch-archive` | 分发归档 | **90 天** | 回溯下游反馈时查原文 |

## 实施步骤（每个环境都做）

### 1. 准备 lifecycle JSON

保存到 `scripts/minio/lifecycle-error-output.json`（已就位则跳过此步）：

```json
{
  "Rules": [
    {
      "ID": "expire-error-output-after-30d",
      "Status": "Enabled",
      "Expiration": { "Days": 30 }
    }
  ]
}
```

其他 bucket 各自一份，Days 按上表调整。

### 2. 用 `mc` CLI 下发

```bash
# 假设 alias 已用 mc alias set batch-mc 做好
mc ilm import batch-mc/batch-error-output < scripts/minio/lifecycle-error-output.json
mc ilm import batch-mc/batch-import-staging < scripts/minio/lifecycle-import-staging.json
mc ilm import batch-mc/batch-export-draft   < scripts/minio/lifecycle-export-draft.json
mc ilm import batch-mc/batch-dispatch-archive < scripts/minio/lifecycle-dispatch-archive.json

# 验证
mc ilm ls batch-mc/batch-error-output
```

### 3. 验证生效

- `mc ilm ls` 能看到规则
- 取一条时间戳 ≥ retention 的旧对象，等下一次 lifecycle scan（默认每天一次），
  `mc stat` 会看到 `X-Amz-Expiration` header

### 4. Grafana 面板

- MinIO exporter 已开（`minio_bucket_usage_total_bytes`）
- 面板 `MinIO Bucket Usage`（panel ID 23）按 bucket 聚合；预期
  `batch-error-output` 曲线在部署 lifecycle 后开始回落或趋平

## 注意事项

1. **不清空应用层配置**：设计文档 §9.11 的 `errorOutputRetentionDays` 字段保留，
   后续若需按租户定制过期策略，再从 lifecycle 平移到应用层。当前统一策略优先。
2. **审计豁免**：某些合规场景下坏行需要保留 >30 天——为此类租户建专属 bucket
   并设独立 rule，**不要**把整个 `batch-error-output` retention 加长。
3. **生产前必须做灰度**：先在 dev / staging 环境跑 1 周确认规则生效 + 数据不误删，
   再推 prod。
4. **灾备**：lifecycle 是 MinIO 单集群配置；若走 multi-site 复制需要在主 / 从各自配。

## 相关

- v3 分析报告：`docs/analysis/deep-issue-analysis-v3.md` R-4.6 条
- 设计文档：`docs/design/批量调度系统设计说明.md` §9.11
- 容量规划：`docs/design/scalability-ha-assessment.md`
