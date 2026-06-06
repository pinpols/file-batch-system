# FilesystemObjectStore 部署说明（Phase 2 阶段二）

> 状态：阶段二落地。日期 2026-06-06。
> 适用 `batch.storage.backend=filesystem` 模式（NAS / 本地 POSIX 文件系统当主存储）。S3 模式无需阅读本文档。
> 设计原文 → [`docs/design/object-storage-abstraction.md`](../design/object-storage-abstraction.md) §4 / §5 / §8。

## 1. 何时选 FS 后端

| 场景 | 选 FS | 选 S3 |
|---|---|---|
| 内网无 MinIO / S3-兼容服务、有现成 NAS | ✅ | |
| 边缘 / 单机 / 离线部署 | ✅（强制 `partitionCount=1`） | |
| 公网 / 大并发下载 / 多区可用 | | ✅（存储直发签名 URL） |
| 测试 / E2E | ✅（指向 `@TempDir`） | |

FS 兼任测试替身，所以不再单列 in-memory store。

## 2. 配置示例

```yaml
batch:
  storage:
    backend: filesystem
    filesystem:
      # FS 后端根目录（绝对路径）。bucket → root/<bucket>/，key → 该目录下相对路径。
      root: /nas/batch
      # presign 返回的应用下载端点 URL 前缀，必填。生产指向 console 内网域名。
      download-base-url: https://console.internal/api/console/files/fs-download
      # presign HMAC 密钥。留空时回落到 batch.security.internal-secret（复用平台内部密钥）。
      presign-secret: ${BATCH_INTERNAL_SECRET:}
      # presign 默认 TTL（兜底，调用方仍优先用入参）。
      default-presign-ttl: 5m
```

加密装饰层（可选，默认 false 保持零回归）：

```yaml
batch:
  storage:
    encryption:
      decorator-enabled: false   # 当前阶段保留 StoreStep manual encryption；切 true 前先迁移
```

## 3. NAS 多主机要求

**所有 worker / orchestrator / console 必须挂载同一个 NAS 共享，且 `root` 路径一致**。否则：

- import worker A 写入 `root/ingress/x.csv` 后，分区 worker B 在另一主机读不到 → 任务卡死
- console-api 走 FS presign 端点回放下载时，若所在主机未挂载同一 NAS，端点找不到对象 → 404

**纯本地盘（无 NAS）约束**：要么单主机部署所有角色，要么强制 `partitionCount=1`（分区数决策处加约束，避免跨主机分发）。

## 4. mount 选项推荐（NFS）

```
mount -t nfs <server>:/export/batch /nas/batch \
  -o nfsvers=4.1,actimeo=0,sync,hard,timeo=600
```

- **`actimeo=0`**：禁用属性缓存。FS 的 `list` etag 走 `size + mtime` 合成；NFS 客户端属性缓存会让 mtime 在客户端"凝固"几秒，导致刚写入的对象 etag 看起来没变，`ImportIngressScanner` 漏检 → 强烈建议关。
- **`sync`**：写穿透（不用客户端 write-back）。配合 `put` 原子写路径里的 `FileChannel.force(true)` fsync，保证 rename 后断电不丢数据。
- **`hard`**：网络抖动时阻塞重试而非返回 EIO（避免业务路径误判存储故障）。

**ATOMIC_MOVE 兼容性**：`FilesystemObjectStore.put` 默认走 `Files.move(StandardCopyOption.ATOMIC_MOVE)`。绝大多数 Linux 本地 FS / NFSv4 都支持；若挂的是某些 NFSv3 或异构 FS 抛 `AtomicMoveNotSupportedException`，自动 fallback `REPLACE_EXISTING` 并 warn。**fallback 路径下短暂窗口可能让并发 `list` 看到 0 字节文件**——内网批量场景可接受，公网高并发请用 S3 后端。

## 5. presign 端点带宽考虑

FS 模式 presign 返回的是 console-api `/api/console/files/fs-download` 端点 URL，字节流经应用进程（不是 S3 那种存储直发签名 URL）：

- **大文件 + 高并发下载 → 应用网卡成瓶颈**。压测推荐：每 worker pod 每秒下载体积 ≤ pod 出方向带宽 / 2。
- **安全模型**：URL 自带 HMAC 令牌即授权（无 Cookie / 无登录态），端点在 `ConsoleSecurityConfiguration` 白名单 `permitAll`。默认 TTL 5 分钟，建议 ≤ 几分钟（令牌不绑 IP、不含内容哈希，长 TTL 容易被泄露后重放）。
- **审计**：当前端点未叠加额外审计；若需要追踪下载来源，请在 ingress / API gateway 层加访问日志（推荐方案，避免重复实现）。

## 6. 路径安全

- `key` 含 `..` 或以 `/` 开头 → 端点 / `FilesystemObjectStore` 直接拒绝（`ObjectStoreException` / 400）。
- bucket 含 `/` / `\` / `..` → 拒绝。
- 实际解析后必须仍在 `root/<bucket>/` 内（`normalize().startsWith(bucketRoot)` 校验）。

与 `DispatchFileContentResolver` 既有 traversal 校验语义对齐（拒 `..` 序列 + 规范化后必须在根内）。

## 7. 已知限制

- **etag = `size + mtime` 合成**（非内容哈希）。若"原地改写但 size + mtime 不变"（罕见），scanner 漏检。
- **加密 × `getFrom` 不兼容**（设计 §5 写死）：`EncryptingObjectStore.getFrom` 抛 `UnsupportedOperationException`。range-slice 分区优化本就只对未加密文件生效，两者天然不相交。
- **list 大目录性能**：FS 走 `Files.walk` 全量扫描后排序 + 分页。bucket 单层超过 10 万对象时 list 延迟显著上升，建议按业务日期 / 租户分目录组织 key。
