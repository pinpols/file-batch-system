#!/bin/sh
# 用途：初始化业务分片：在 batch_business 库执行 scripts/db/business/create_biz_tables.sql。
# 用法：由对应容器的 entrypoint/init 配置调用；环境参数见容器配置与下方说明。
# 初始化业务分片：在 batch_business 库执行 scripts/db/business/create_biz_tables.sql。
# create_biz_tables.sql 不自带 \connect(见其头注释:必须 -d batch_business 执行),
# 故这里显式 -d batch_business,不能直接丢进 initdb.d(否则会落到默认库污染 schema)。
set -e
echo "[biz-shard-1] loading biz tables into batch_business ..."
psql -v ON_ERROR_STOP=1 -U "${POSTGRES_USER}" -d batch_business -f /biz-sql/create_biz_tables.sql
echo "[biz-shard-1] biz tables loaded."
# RLS(rls-phase-a.sql)按需另行施加,与 shard-0(primary)dev 默认态保持一致——
# 默认不施加;要 pooled-sharding 内租户隔离时,见 secrets/biz-shards/README.md。
