-- 仅观察当前隔离测试库的活跃客户端；不把采样数直接换算成等待时长。
select coalesce(wait_event_type, 'NO_REPORTED_WAIT') || ':' || coalesce(wait_event, '')
from pg_stat_activity
where datname = current_database()
  and backend_type = 'client backend'
  and state = 'active'
  and pid <> pg_backend_pid()
