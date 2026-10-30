# MinIO / 对象存储治理脚本

本目录保存 S3 兼容对象存储的 lifecycle 策略与下发入口。脚本可用于本地
MinIO，也可用于生产 S3 兼容服务；生产执行必须使用具备 lifecycle 管理权限的专用账号。

## 文件

- `apply-lifecycle.sh`：按 `local` / `test` / `benchmark` / `prod` profile 预览或下发 lifecycle。
- `lifecycle-profiles/*.env`：bucket 与策略文件的映射。`main` 表示批量文件 bucket，`ai` 表示 Console AI 附件 bucket。
- `lifecycle-*.json`：实际导入对象存储的 lifecycle JSON。

## 示例

```bash
# 只预览
scripts/minio/apply-lifecycle.sh --environment local

# 本地下发
scripts/minio/apply-lifecycle.sh --environment local --apply

# 生产下发必须显式确认，并传入真实 endpoint、bucket 和专用凭据
MINIO_ENDPOINT=https://s3.prod.example.com \
MINIO_ROOT_USER="$S3_LIFECYCLE_ADMIN_ACCESS_KEY" \
MINIO_ROOT_PASSWORD="$S3_LIFECYCLE_ADMIN_SECRET_KEY" \
MINIO_BUCKET=batch-prod \
MINIO_AI_ATTACHMENT_BUCKET=batch-ai-attachments \
MINIO_LIFECYCLE_PROD_ACK=I_UNDERSTAND_PRODUCTION_LIFECYCLE \
  scripts/minio/apply-lifecycle.sh --environment prod --apply
```

完整策略说明见
[`docs/runbook/object-storage-lifecycle-policy.md`](../../docs/runbook/object-storage-lifecycle-policy.md)。

## 脚本与配套 SQL 索引

本表登记本目录的执行入口、公共模块及配套 SQL；测试代码通过测试命令执行。新增、移动或删除资产时同步跳转和用途，SQL 正文保持独立维护。

| 文件 | 用途 / 调用归属 |
|---|---|
| [apply-lifecycle.sh](apply-lifecycle.sh) | 对象存储 lifecycle 下发脚本。默认只预览；生产 apply 必须显式确认。 |
