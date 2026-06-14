-- biz shard-1 初始化:建 batch_business 库 + biz schema(镜像 docker/postgres/init/000-create-business-db.sql)。
-- shard-1 是 P2 tenant-routing 的第二片真实 PG 实例;shard-0 = postgres-primary 的 batch_business。
-- 两片各自独立 PG 进程/数据卷,租户经应用层 BusinessRoutingDataSource 按 placement key 路由到其一。
SELECT 'CREATE DATABASE batch_business'
WHERE NOT EXISTS (
    SELECT 1
    FROM pg_database
    WHERE datname = 'batch_business'
)\gexec

\connect batch_business

CREATE SCHEMA IF NOT EXISTS biz;
COMMENT ON SCHEMA biz IS 'Business data schema (tenant-routing shard-1).';
