# 对象存储后端（S3 协议）配置与多云接入

平台对象存储底层用 AWS SDK for Java v2(`software.amazon.awssdk:s3`)——一个**通用 S3 协议客户端**。同一套配置(前缀 `batch.storage.s3`)可接
**自建 MinIO / Ceph、AWS S3、阿里云 OSS（S3 兼容）、腾讯云 COS（S3 兼容）**:换后端只改配置,**不换 SDK、不改业务代码、不加依赖**——后端切换语义不变,客户端始终是通用 S3 协议实现。

> **License 提示**:AWS SDK for Java v2(`software.amazon.awssdk:s3`)= Apache 2.0;AWS S3 / 阿里 OSS / 腾讯 COS 是商业云服务,按云厂商合同和组织采购流程管理。自建 MinIO / Ceph / RustFS 等 S3 兼容服务必须在部署前完成镜像来源、版本、许可证、维护支持和业务使用方式审查;本仓本地 MinIO 仅作为开发 / 集成测试默认后端。生产基线见 [`base-services-deployment.md`](./base-services-deployment.md)。

> 非 S3 协议的 Azure Blob / GCS 不在此列——那需要存储抽象层 + 独立后端实现(另见规划)。

## 存储域与桶边界

对象存储按访问主体、保留周期和故障域分配，不能把所有对象集中到默认业务桶。

| 存储域 | 桶或承载方式 | 状态与边界 |
|---|---|---|
| 批量业务文件 | `batch.storage.s3.bucket` | 已实现；承载导入、导出、中间结果、错误明细和完整性标记，由文件域统一登记生命周期。 |
| Console AI 私有附件 | `batch.console.ai.attachment.storage-bucket`，默认 `batch-ai-attachments` | 已实现；当前仅允许图片，必须与批量文件桶不同，不提供匿名读取、公共 CDN、客户端直传或预签名访问。 |
| 系统文档源码 | Git | 不进入运行时对象存储；代码评审、版本和追溯均以 Git 为准。 |
| 构建后的静态文档站 | 独立发布桶或文档托管平台 | 可选规划；自托管时使用独立桶、只读发布身份和 CDN，不复用业务文件或 AI 附件桶。 |
| 审计取证包 | 独立合规桶 | 规划项；落地时要求版本控制、对象锁或 WORM、独立保留策略和审计读取权限。 |
| PostgreSQL 备份与 WAL | 集群外独立对象存储 | 已有运维路径；生产必须与应用对象存储处于不同账号、集群或区域，避免同故障域失效。 |
| 日志、指标与 Trace 冷数据 | 观测平台自己的对象存储 | 由 Loki、Tempo 等观测组件管理，不经过应用文件 API，也不写入业务桶。 |
| CI 制品、SBOM 与测试报告 | CI artifact 或制品库 | 不写入运行时对象存储，由发布系统管理保留期和下载权限。 |

独立桶不等于独立故障域。AI 附件当前复用统一 S3 客户端凭据，但通过独立桶获得生命周期和桶策略隔离；高敏部署可进一步为 Console API 配置限定两个桶的最小权限身份。备份必须使用真正独立的故障域，不能只在同一 MinIO 集群内换桶名。

## 配置项(`batch.storage.s3.*`)

| 键 | 环境变量 | 说明 |
|---|---|---|
| `endpoint` | `BATCH_S3_ENDPOINT` | 访问地址(http/https) |
| `access-key` | `BATCH_S3_ACCESS_KEY` | 访问密钥 ID（腾讯 COS 为 SecretId） |
| `secret-key` | `BATCH_S3_SECRET_KEY` | 秘密访问密钥 |
| `bucket` | `BATCH_S3_BUCKET` | 默认 bucket |
| `region` | `BATCH_S3_REGION` | **AWS/OSS/COS 走 SigV4 必填**;自建 S3 兼容服务可留空，运行时回退 `us-east-1` |
| `auto-create-bucket` | `BATCH_S3_AUTO_CREATE_BUCKET` | bucket 不存在时自动创建。自建默认 `true`;**托管云设 `false`**(bucket 预建、凭据无 CreateBucket 权限) |
| `path-style-enabled` | `BATCH_S3_PATH_STYLE_ENABLED` | 自建 S3 兼容服务通常设 `true`;AWS S3 标准域名、部分云厂商可设 `false` |
| `request-checksum-calculation` | `BATCH_S3_REQUEST_CHECKSUM_CALCULATION` | 默认 `WHEN_REQUIRED`,避免非 AWS 后端不兼容 aws-chunked trailer |
| `response-checksum-validation` | `BATCH_S3_RESPONSE_CHECKSUM_VALIDATION` | 默认 `WHEN_REQUIRED`,与请求侧保持兼容优先 |
| `connect-timeout-ms` | `BATCH_S3_CONNECT_TIMEOUT_MS` | 连接超时 |
| `read-timeout-ms` | `BATCH_S3_READ_TIMEOUT_MS` | socket 读写空闲超时 |
| `max-attempts` | `BATCH_S3_MAX_ATTEMPTS` | `0` 表示使用 AWS SDK 默认重试策略 |
| `adaptive-retry` | `BATCH_S3_ADAPTIVE_RETRY` | 是否启用 SDK 自适应重试 |

## 各后端示例

**自建 MinIO（默认）**
```yaml
batch.storage.s3:
  endpoint: http://minio:9000
  access-key: minioadmin
  secret-key: ${BATCH_S3_SECRET_KEY}
	  bucket: batch-prod
	  # region 留空,auto-create-bucket/path-style-enabled 默认 true
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
	  path-style-enabled: false
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

## 常见问题

1. **region 必填**:AWS/OSS/COS 缺 region 会 SigV4 签名失败(`SignatureDoesNotMatch` / `AuthorizationHeaderMalformed`)。
2. **建桶权限**:托管云务必 `auto-create-bucket: false` + bucket 预建,否则启动期撞 `AccessDenied`。
3. **path-style**:自建 S3 兼容服务通常用 `true`;AWS S3 标准域名推荐 `false`。
4. **checksum**:多数 S3 兼容后端不支持 AWS SDK v2 的 aws-chunked trailer,默认 `WHEN_REQUIRED` 更稳。
5. **COS bucket 名**:强制 `name-APPID` 格式。
6. **HTTPS**:托管云一律 `https://`。

## 本地 MinIO `mc` 常用命令

本地 compose 已包含 `minio-init`，镜像内自带 `mc`。不想在宿主机安装 `mc` 时，先定义一个临时 helper：

```bash
mcli() {
  docker compose --env-file .env.local run --rm --entrypoint sh minio-init -lc \
    'mc alias set local "$MINIO_ENDPOINT" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null; mc "$@"' \
    sh "$@"
}
```

如果宿主机已安装 `mc`，可直接配置本机 alias：

```bash
mc alias set batch-local http://localhost:19000 minioadmin minioadmin123
```

下列示例默认使用上面的 `mcli` helper；本机 `mc` 模式下把 `mcli` 替换成 `mc`，并把 `local/` 替换成 `batch-local/` 即可。

### 基础巡检

```bash
# 查看服务信息。
mcli admin info local

# 查看 bucket 列表。
mcli ls local

# 确认本地默认 bucket 存在。
mcli ls local/batch-dev

# 查看 bucket 体积。
mcli du local/batch-dev
```

### 查看对象

```bash
# 查看一级目录。
mcli ls local/batch-dev/

# 递归查看 ingress 目录。
mcli ls --recursive local/batch-dev/ingress/

# 只看对象树，适合快速判断目录层级。
mcli tree --files local/batch-dev

# 查看对象元数据、大小、etag、content-type。
mcli stat local/batch-dev/ingress/ta/sample.csv

# 直接读取小文件内容。
mcli cat local/batch-dev/outbound/REPORT/2026-06-24/report.csv | head -20
```

### 上传与下载

```bash
# 上传单个文件到导入扫描目录。
mcli cp ./sample.csv local/batch-dev/ingress/ta/sample.csv

# 上传目录，适合批量投放测试文件。
mcli mirror ./fixtures/import/ local/batch-dev/ingress/ta/

# 下载单个对象到本地目录。
mcli cp local/batch-dev/outbound/REPORT/2026-06-24/report.csv ./tmp/

# 下载整个 outbound 目录。
mcli mirror local/batch-dev/outbound/ ./tmp/outbound/
```

### 查找与过滤

```bash
# 查找 CSV 文件。
mcli find local/batch-dev/ingress/ --name '*.csv'

# 查找最近 1 小时生成的导出对象。
mcli find local/batch-dev/outbound/ --newer-than 1h

# 查找超过 1GiB 的对象。
mcli find local/batch-dev/ --larger 1GiB
```

### 清理对象

```bash
# 删除单个对象。
mcli rm local/batch-dev/ingress/ta/sample.csv

# 清理某个测试批次目录。
mcli rm --recursive --force local/batch-dev/tmp/test-batch-001/

# 清空 bucket 前先 dry-run：只列出将要删除的对象。
mcli rm --recursive --force --dry-run local/batch-dev/
```

### 临时下载链接

```bash
# 生成 10 分钟有效的预签名下载地址，便于排查浏览器或下游下载问题。
mcli share download --expire 10m local/batch-dev/outbound/REPORT/2026-06-24/report.csv
```

### 与系统表联动排查

对象路径通常来自 `file_record.storage_path`、导出任务的 `outputPath` 或分发任务参数。先在数据库定位路径，再用 `mc stat/cat/cp` 校验对象是否存在、内容是否可读。

```bash
# 示例：拿到 storage_path 后检查对象。
mcli stat local/batch-dev/ingress/ta/sample.csv
mcli cat local/batch-dev/ingress/ta/sample.csv | head -5
```

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
