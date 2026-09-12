-- 新建本地数据库时预装 SQL 画像扩展。扩展只有在 postgres 启动参数包含
-- shared_preload_libraries=pg_stat_statements 时才能采集；普通本地/Sim 默认仍关闭预加载。
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
