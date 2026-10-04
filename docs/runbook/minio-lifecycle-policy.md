# S3 兼容对象生命周期策略 runbook

> 针对 R-4.6（错误输出文件永驻对象存储无 TTL）的运维 runbook。
> 维护人：SRE；每半年复盘一次过期天数是否合理。
>
> **License 提示**：MinIO Server = AGPL v3。本系统自托管 + 不改源码 + 用户不直连 → **无 license 风险**（同 Citus / Grafana / Loki / Tempo，license 风险结论一致:无）。

## 背景

`batch-worker-import` 在 validate / load 失败时会把坏行以 NDJSON 写入 S3 兼容对象存储
`batch-error-output` bucket（具体名见 `batch.storage.s3.bucket` 及文件域配置）。
设计文档 §9.11 约定 `errorOutputRetentionDays`，但 worker 层当前**未实现主动清理**。
为避免持续累积占用存储，统一使用 **对象存储 lifecycle rule** 在存储侧做自动过期，
零代码改动即可生效。仓库脚本使用 `mc ilm` 作为 MinIO / S3 兼容服务适配器；AWS S3、
阿里云 OSS、腾讯云 COS 等托管服务也可以用平台侧 lifecycle 配置完成同一目标，不要求
在生产环境安装或运行本仓脚本。

其他**导入/导出过程中的临时中间产物** bucket（预处理 spool 目录、parsed NDJSON、
validated NDJSON、export draft）只要命名固定，都适用同一套策略。

## 标准策略

| bucket | 内容类型 | 保留天数 | 理由 |
|---|---|---|---|
| `batch-error-output` | 坏行 NDJSON（可能含 PII） | **30 天** | 合规审计 + 重试上限；超过应自动脱敏清理 |
| `batch-import-staging` | 预处理 spool / parse 中间文件 | **7 天** | 仅用于断点重试；正常流程不留 |
| `batch-export-draft` | 导出生成中文件 | **3 天** | 正常流程会被 StoreStep 移走；留 3 天处理异常 |
| `batch-dispatch-archive` | 分发归档 | **90 天** | 回溯下游反馈时查原文 |

## 实施步骤

### 1. 选择环境 profile

```bash
# 只预览，不修改
scripts/minio/apply-lifecycle.sh --environment local

# 本地 / 测试 / 压测下发
scripts/minio/apply-lifecycle.sh --environment local --apply
scripts/minio/apply-lifecycle.sh --environment test --apply
scripts/minio/apply-lifecycle.sh --environment benchmark --apply
```

脚本读取 `scripts/minio/lifecycle-profiles/*.env`，将 `main` 映射到
`MINIO_BUCKET` / `BATCH_S3_BUCKET`，将 `ai` 映射到
`MINIO_AI_ATTACHMENT_BUCKET` / `BATCH_CONSOLE_AI_ATTACHMENT_STORAGE_BUCKET`。
具体 lifecycle JSON 位于 `scripts/minio/lifecycle-*.json`。

### 2. 生产下发保护

生产环境默认不自动下发。必须使用具备 lifecycle 管理权限的专用账号，并显式确认。
托管对象存储可直接在云平台控制台、Terraform、Helm values 或 Operator 配置中应用同等策略；
以下命令只是 MinIO / `mc` 兼容环境示例：

```bash
MINIO_ENDPOINT=https://s3.prod.example.com \
MINIO_ROOT_USER="$S3_LIFECYCLE_ADMIN_ACCESS_KEY" \
MINIO_ROOT_PASSWORD="$S3_LIFECYCLE_ADMIN_SECRET_KEY" \
MINIO_BUCKET=batch-prod \
MINIO_AI_ATTACHMENT_BUCKET=batch-ai-attachments \
MINIO_LIFECYCLE_PROD_ACK=I_UNDERSTAND_PRODUCTION_LIFECYCLE \
  scripts/minio/apply-lifecycle.sh --environment prod --apply
```

脚本会拒绝使用本地默认 `minioadmin/minioadmin123` 对生产下发。

### 3. 验证生效

- `mc ilm ls` 或云厂商 lifecycle 查询能看到规则
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
4. **灾备**：lifecycle 是对象存储集群或云桶级配置；若走 multi-site / 跨区域复制，需要在主 / 从或各区域分别配置。

## 相关

- v3 分析报告：`docs/analysis/deep-issue-analysis-v3.md` R-4.6 条
- 设计文档：`docs/archive/design/system-design-2026-03-21.md` §9.11
- 容量规划：`docs/archive/architecture/scalability-ha-assessment-2026-03-26.md`
