-- R2-P1-3: workflow_node_run.(workflow_run_id, node_code) 显式 BTREE 索引
--
-- 现状：DefaultWorkflowDagService.isNodeReadyForDispatch / canNeverFire / advance 路径
-- 反复执行 `SELECT ... WHERE workflow_run_id = ? AND node_code = ? ORDER BY run_seq DESC LIMIT 1`，
-- N+1 模式（每条入边一次）。仅靠 V5 的 `uk_workflow_node_run(workflow_run_id, node_code, run_seq)`
-- 唯一约束 PG 可走索引扫，但 unique 约束在选择性差时 planner 偶尔退化全表。
--
-- 显式 BTREE (workflow_run_id, node_code, run_seq DESC) → 单条 selectLatest 查询的 cost 从
-- 顺序扫秒级降到 ~O(log n) 微秒级；DAG 节点多（>20）且并发 workflow_run >50 时延迟收益明显。
--
-- IF NOT EXISTS 守护：如果某个集群通过手动 SQL 已建过同等索引（pg_dump → restore 场景），不报错。
CREATE INDEX IF NOT EXISTS idx_workflow_node_run_run_id_node_code
    ON batch.workflow_node_run (workflow_run_id, node_code, run_seq DESC);
