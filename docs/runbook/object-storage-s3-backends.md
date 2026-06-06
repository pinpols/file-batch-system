# 对象存储后端（S3 协议）配置与多云接入

平台对象存储底层用 MinIO Java SDK——一个**通用 S3 协议客户端**。同一套配置(前缀 `batch.storage.s3`)可接
**自建 MinIO / Ceph、AWS S3、阿里云 OSS（S3 兼容）、腾讯云 COS（S3 兼容）**:换后端只改配置,**不换 SDK、不改业务代码、不加依赖**。

> **License 提示**:MinIO Server = AGPL v3(自建场景);MinIO Java SDK = Apache 2.0;AWS S3 / 阿里 OSS / 腾讯 COS 是商业云服务无 license 问题。**本系统自托管 MinIO + 不改源码 + 用户不直连 → 无 license 风险**(同 Citus / Grafana / Loki / Tempo,统一评估见 [`docs/backlog/citus-introduction-plan-2026-06-06.md`](../backlog/citus-introduction-plan-2026-06-06.md) §11)。

> 非 S3 协议的 Azure Blob / GCS 不在此列——那需要存储抽象层 + 独立后端实现(另见规划)。

## 配置项(`batch.storage.s3.*`)

| 键 | 环境变量 | 说明 |
|---|---|---|
| `endpoint` | `BATCH_S3_ENDPOINT` | 访问地址(http/https) |
| `access-key` | `BATCH_S3_ACCESS_KEY` | 访问密钥 ID（腾讯 COS 为 SecretId） |
| `secret-key` | `BATCH_S3_SECRET_KEY` | 秘密访问密钥 |
| `bucket` | `BATCH_S3_BUCKET` | 默认 bucket |
| `region` | `BATCH_S3_REGION` | **AWS/OSS/COS 走 SigV4 必填**;自建 MinIO/Ceph 留空 |
| `auto-create-bucket` | `BATCH_S3_AUTO_CREATE_BUCKET` | bucket 不存在时自动创建。自建默认 `true`;**托管云设 `false`**(bucket 预建、凭据无 CreateBucket 权限) |

## 各后端示例

**自建 MinIO（默认）**
```yaml
batch.storage.s3:
  endpoint: http://minio:9000
  access-key: minioadmin
  secret-key: ${BATCH_S3_SECRET_KEY}
  bucket: batch-prod
  # region 留空,auto-create-bucket 默认 true
```

**AWS S3**
```yaml
batch.storage.s3:
  endpoint: https://s3.us-east-1.amazonaws.com
  region: us-east-1
  access-key: ${AWS_ACCESS_KEY_ID}
  secret-key: ${AWS_SECRET_ACCESS_KEY}
  bucket: my-batch-bucket
  auto-create-bucket: false
```

**阿里云 OSS（S3 兼容模式）**
```yaml
batch.storage.s3:
  endpoint: https://oss-cn-hangzhou.aliyuncs.com
  region: oss-cn-hangzhou
  access-key: ${OSS_ACCESS_KEY_ID}
  secret-key: ${OSS_ACCESS_KEY_SECRET}
  bucket: my-batch-bucket
  auto-create-bucket: false
```

**腾讯云 COS（S3 兼容）**
```yaml
batch.storage.s3:
  endpoint: https://cos.ap-guangzhou.myqcloud.com
  region: ap-guangzhou
  access-key: ${COS_SECRET_ID}
  secret-key: ${COS_SECRET_KEY}
  bucket: my-batch-bucket-1250000000   # COS bucket 名强制带 APPID 后缀
  auto-create-bucket: false
```

## 常见坑

1. **region 必填**:AWS/OSS/COS 缺 region 会 SigV4 签名失败(`SignatureDoesNotMatch` / `AuthorizationHeaderMalformed`)。
2. **建桶权限**:托管云务必 `auto-create-bucket: false` + bucket 预建,否则启动期撞 `AccessDenied`。
3. **COS bucket 名**:强制 `name-APPID` 格式。
4. **HTTPS**:托管云一律 `https://`。

## ⚠ 迁移说明(2026-06-06：`minio` 前缀更名为 `s3`)

配置前缀从 `batch.storage.minio` 改为 `batch.storage.s3`,对应环境变量 `BATCH_MINIO_*` 改为 `BATCH_S3_*`:

| 旧 | 新 |
|---|---|
| `batch.storage.minio.*` | `batch.storage.s3.*` |
| `BATCH_MINIO_ENDPOINT` | `BATCH_S3_ENDPOINT` |
| `BATCH_MINIO_ACCESS_KEY` | `BATCH_S3_ACCESS_KEY` |
| `BATCH_MINIO_SECRET_KEY` | `BATCH_S3_SECRET_KEY` |
| `BATCH_MINIO_BUCKET` | `BATCH_S3_BUCKET` |

**部署侧必须同步**:显式设置过 `BATCH_MINIO_*` 的环境(`.env` / K8s ConfigMap/Secret / CI)需改成 `BATCH_S3_*`,
否则将回退到默认值(localhost MinIO)导致连不上生产存储。仅依赖 `:-default` 缺省值的环境不受影响。

> 注:**MinIO 服务器容器**自身的变量(`MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` / `MINIO_BUCKET` / `MINIO_*_PORT` 等)
> 是 MinIO 产品配置,**未改名**,保持原样。
