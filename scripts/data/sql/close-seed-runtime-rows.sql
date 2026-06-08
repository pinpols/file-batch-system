UPDATE batch.job_instance SET instance_status='TERMINATED', finished_at=COALESCE(finished_at, now())
  WHERE instance_status IN ('CREATED','WAITING','READY','RUNNING','RETRYING');
UPDATE batch.job_partition SET partition_status='TERMINATED', finished_at=COALESCE(finished_at, now())
  WHERE partition_status IN ('CREATED','WAITING','READY','RUNNING','RETRYING');
UPDATE batch.job_task SET task_status='TERMINATED', finished_at=COALESCE(finished_at, now())
  WHERE task_status IN ('CREATED','WAITING','READY','RUNNING','RETRYING');
UPDATE batch.job_step_instance SET step_status='TERMINATED', finished_at=COALESCE(finished_at, now())
  WHERE step_status IN ('CREATED','WAITING','READY','RUNNING','RETRYING');
UPDATE batch.pipeline_instance SET run_status='TERMINATED', finished_at=COALESCE(finished_at, now())
  WHERE run_status IN ('CREATED','RUNNING','COMPENSATING');
UPDATE batch.workflow_run SET run_status='TERMINATED', finished_at=COALESCE(finished_at, now())
  WHERE run_status IN ('CREATED','WAITING','READY','RUNNING','RETRYING');
-- workflow_node_run.node_status 的 CHECK 约束(ck_workflow_node_run_status)只允许
-- READY/RUNNING/SUCCESS/FAILED/SKIPPED,不接受 TERMINATED(其它 batch.* 表的命名)。
-- 用 FAILED 收尾"未跑完"的 in-progress 节点行,与上下游收尾语义对齐。
UPDATE batch.workflow_node_run SET node_status='FAILED', finished_at=COALESCE(finished_at, now())
  WHERE node_status IN ('CREATED','WAITING','READY','RUNNING','RETRYING');
