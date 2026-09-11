-- 基础依赖巡检只读查询；不要在此文件加入修复或清理语句。
select current_database()
       || '|' || current_setting('server_version')
       || '|' || pg_is_in_recovery()::text
       || '|' || (select count(*) from pg_stat_activity);
