-- DANGER: 先 DROP 再创建目标数据库。只允许由 dr-drill.sh 对本机临时恢复库调用。
DO $$
BEGIN
  IF current_setting('batch.destructive_ops', true) IS DISTINCT FROM 'dr-drill' THEN
    RAISE EXCEPTION '拒绝执行 DROP DATABASE:需通过本地 dr-drill 入口显式授权';
  END IF;
END $$;
SELECT format('DROP DATABASE IF EXISTS %I', :'database_name');
\gexec
SELECT format('CREATE DATABASE %I', :'database_name');
\gexec
-- DANGER: 先 DROP 再创建目标 PostgreSQL 数据库。只允许由 dr-drill.sh 对本机临时恢复库调用。
