# 容器启动资源

本目录放镜像启动入口与数据库容器初始化资源；本地构建、启停入口见 [容器脚本](../../scripts/docker/README.md)。SQL 初始化文件与 Flyway 迁移分别维护，不按普通脚本头格式重写。

## 脚本与配套 SQL 索引

本表登记本目录的执行入口、公共模块及配套 SQL；测试代码通过测试命令执行。新增、移动或删除资产时同步跳转和用途，SQL 正文保持独立维护。

| 文件 | 用途 / 调用归属 |
|---|---|
| [biz-shard-init/001-load-biz-tables.sh](biz-shard-init/001-load-biz-tables.sh) | biz shard-1 建表:把 scripts/db/business/create_biz_tables.sql 灌入 batch_business 库。 |
| [entrypoint.sh](entrypoint.sh) | entrypoint.sh — 通用应用容器启动入口。 |
| [postgres/init/003-create-replication-user.sh](postgres/init/003-create-replication-user.sh) | P2-4: 创建流复制用户 replicator + 放行 replica 容器在 batch-network 子网的连接。 |
| [postgres-replica/entrypoint.sh](postgres-replica/entrypoint.sh) | P2-4: postgres-replica 容器入口脚本。 |
