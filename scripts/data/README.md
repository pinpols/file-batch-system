# 数据初始化脚本

这里放平台依赖和测试数据的初始化入口。脚本不绑定某一种部署形态；本地 Compose 只是默认 fallback。

## 脚本

- `init-kafka-topics.sh`：创建平台默认 Kafka topics。
- `init-tenant-topics.sh`：按租户创建隔离 topic。
- `init-tenant-kafka-acl.sh`：按租户初始化 Kafka ACL。
- `init-minio.sh`：初始化 S3 兼容对象存储 bucket 和基础策略。
- `load-system-test-data.sh`：加载系统测试数据。
- `init-coverage-tenants.py`：本地 ta/tb/tc 覆盖租户预检及受控初始化；见下方保护边界。

`sql/` 下是这些入口使用的辅助 SQL。正式 schema 变更请放在 [../../db/migration/](../../db/migration/)。

## 本地覆盖租户初始化

`scripts/data/init-coverage-tenants.py` 仅接受显式 loopback Console 地址。默认检查 ta/tb/tc 不存在及目标包哈希，真实认证模式需登录，可能产生认证审计记录；不创建租户或导入配置。`--apply` 才逐个创建 tenant、上传对应 `coverage-examples`、预览、导出空配置检查和应用。配置落盘会创建作业、工作流及其他配置；示例运行仍需对应 Worker、业务端点、表与 manifest，并不保证业务作业可执行。

示例（用自己的仓库外路径，勿提交凭据和回执）：

```bash
python3 scripts/data/init-coverage-tenants.py --base-url http://127.0.0.1:18080 \
  --operator coverage-init --receipt /private/tmp/coverage-receipt.json --allow-local-bypass
```

受控执行时追加 `--apply --credentials-file /private/tmp/coverage-credentials.json --acknowledge-insert-only-limitation`；实际地址与文件由操作者指定。脚本生成三组不同的随机强密码及 `ta-admin`、`tb-admin`、`tc-admin`，凭据文件以 0600 创建，已存在不会覆盖；可选 `--prompt-password` 分别隐藏输入密码。回执记录本机地址、操作者、文件哈希、阶段、请求键和统计，不含密码、Cookie 或预览令牌。回执和凭据需要妥善保管，不能提交仓库。`--allow-local-bypass` 仅明确允许本机匿名 bypass，不代表权限已验收。

**当前接口的限制：**预览未给出新增/更新分类；导出排除软删除数据，导入 upsert 可恢复或覆盖同名软删除项。预览版本令牌能阻断预览之后的配置变化，但不能证明预览前不存在隐蔽项，也不能证明同一地址始终连接同一数据库。执行前必须人工核对目标库及软删除数据、排除并发写入；此脚本不能提供事务内绝不覆盖的保证。实际回执出现更新或行数不一致时停止并保留未知状态，不声称回滚已提交数据。

脚本只认领有本次回执且 ID、操作者一致的租户，其他既有租户立即停止。创建或应用阶段响应丢失时不重发、不自动认领，需人工核对；不执行跨租户清理。正常已完成阶段可使用相同回执和凭据续跑；包、操作者或地址变化会被拒绝。

## 外部环境

这些脚本不要求必须进 Docker 容器，但需要本机安装对应客户端并通过环境变量指定连接信息：

- Kafka topic 初始化：安装 Kafka CLI，确保 `kafka-topics.sh` 在 `PATH`，或设置 `KAFKA_BIN_DIR=/path/to/kafka/bin` / `KAFKA_TOPICS_BIN=/path/to/kafka-topics.sh`；连接地址用 `KAFKA_BOOTSTRAP_SERVER`。初始化器始终确保平台核心 topic 存在，`KAFKA_TOPICS` 仅用于追加自定义 topic，不能用旧本地环境文件覆盖核心清单。
- 对象存储初始化：安装 `mc`；连接地址和凭据用 `MINIO_ENDPOINT`、`MINIO_ROOT_USER`、`MINIO_ROOT_PASSWORD`、`MINIO_BUCKET`。托管云对象存储也可由平台侧预建 bucket 和 lifecycle，此脚本只是 S3 兼容初始化适配器。
- 系统测试数据加载：数据库连接用 `PGHOST`、`PGPORT`、`PGUSER`、`PGPASSWORD`、`PLATFORM_DB`、`BUSINESS_DB`；默认按宿主机 `psql`、Python `psycopg`、Docker 客户端顺序选择，也可设置 `BATCH_PSQL_BIN` 或 `BATCH_PG_CLIENT_MODE`。Python fallback 依赖 `scripts/requirements-postgres.txt`。对象存储用 `BATCH_S3_*`，无可用客户端时明确失败。

## 脚本与配套 SQL 索引

本表登记本目录的执行入口、公共模块及配套 SQL；测试代码通过测试命令执行。新增、移动或删除资产时同步跳转和用途，SQL 正文保持独立维护。

| 文件 | 用途 / 调用归属 |
|---|---|
| [init-coverage-tenants.py](init-coverage-tenants.py) | 本地覆盖租户预检、创建和配置包导入；当前接口不保证事务内仅新增。 |
| [init-kafka-topics.sh](init-kafka-topics.sh) | 初始化 Kafka topics。 |
| [init-minio.sh](init-minio.sh) | 初始化 S3 兼容对象存储 bucket。 |
| [init-tenant-kafka-acl.sh](init-tenant-kafka-acl.sh) | ADR-035 Phase 3:per-tenant Kafka SASL/SCRAM 用户 + ACL。 |
| [init-tenant-topics.sh](init-tenant-topics.sh) | 为 per-tenant worker pool 创建独立派发 topic。 |
| [load-system-test-data.sh](load-system-test-data.sh) | 装载系统测试数据。 |
| [sql/align-batch-id-sequences.sql](sql/align-batch-id-sequences.sql) | 供 [load-system-test-data.sh](load-system-test-data.sh) 使用的配套 SQL。 |
| [sql/close-seed-runtime-rows.sql](sql/close-seed-runtime-rows.sql) | 供 [load-system-test-data.sh](load-system-test-data.sh) 使用的配套 SQL。 |
