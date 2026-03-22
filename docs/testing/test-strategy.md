# 测试分层建议

目标是覆盖核心链路和典型失败场景，但不把测试数量扩成全组合穷举。建议按三层拆分：单元测试负责纯逻辑，集成测试负责模块协作，端到端测试只保留少量主路径。

## 单元测试

只测不依赖外部系统的逻辑，重点放在分支多、规则密、回归风险高的地方。

- 状态机和枚举流转：`job_instance`、`job_partition`、`job_task`、`pipeline_instance`、`file_record`、`approval_command`、`dead_letter_task`、`retry_schedule`
- 调度规则：窗口、日历、catch-up、`quota_reset_policy`、租约回收、WAITING 出队、Worker 排空判定
- 文件链规则：模板解析、字段映射、脱敏开关、加密开关、导出格式选择、`cursor / keyset` 翻页
- 分发规则：通道路由、`config_json` 合并保护、registry 重复 id、回执策略判定、健康状态切换
- 工具类：idempotency、dedup、错误码转换、掩码规则、分页游标推进

## 集成测试

只测真实依赖下的模块协作，建议用 Postgres、Kafka、MinIO，再加少量 HTTP mock。

- `batch-orchestrator`
  - Outbox -> Kafka
  - Retry scheduler -> retry / dead letter 推进
  - Partition lease reclaim
  - Quota runtime reset
  - Worker drain timeout
  - SLA / alert 落库
- `batch-worker-import`
  - 入口扫描 -> parse -> validate -> load
  - 成功样本、校验失败样本、流式大文件样本
- `batch-worker-export`
  - business table -> generate -> store -> register
  - 成功样本、加密样本、大分页样本
- `batch-worker-dispatch`
  - API / API_PUSH
  - NAS / OSS
  - EMAIL / SFTP
  - receipt poll、health probe、circuit breaker
- `batch-console-api`
  - 查询、下载、审批、DLQ replay

## 端到端测试

只保留 4 条主路径，避免测试爆炸。

- 导入主链路：上游文件 -> 扫描 -> parse -> validate -> load -> 业务表落库
- 导出主链路：业务表 -> 生成文件 -> 加密/存储 -> 注册 -> 分发 -> 回执
- 补偿审批链路：失败任务 -> 审批 -> replay / compensation 成功
- 治理闭环链路：失败分发 / DLQ / alert / retry / health probe

## 建议配比

- 单元测试：最多，覆盖约 60% 的逻辑面
- 集成测试：中等，覆盖约 25% 的模块协作
- 端到端测试：少而精，覆盖约 15% 的核心链路

## 当前优先级

1. 先补单元测试，把纯逻辑和分支风险压下去。
2. 再补最小集成测试，确保主链路不依赖人工。
3. 最后用系统测试种子做端到端验证，主要覆盖导入、导出、分发、审批和治理闭环。
