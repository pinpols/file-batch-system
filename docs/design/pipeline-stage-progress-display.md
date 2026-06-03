# Pipeline Stage 进度展示策略

> 2026-06-03 备案。回答"要不要给 pipeline stage 加进度条"。

## 现状

stage 级**成功 / 失败 / 错误信息 / 起止时间**已经在 3 处 FE 详情页展示:

| 详情页 | 字段 |
|---|---|
| `JobInstanceDetail` Steps tab | stepCode / stepStatus / retry / start / finish / error |
| `WorkflowRunDetail` node-runs | nodeCode / nodeStatus + 筛选 |
| `FilePipelineObservability` Steps tab | stepCode / stageCode / stepStatus / errorMessage |

未展示的是 stage **内部**"已经跑到第几行 / 还剩多久"。

## 决策

| 类型 | 进度展示 | 方式 |
|---|---|---|
| **IMPORT LOAD / EXPORT GENERATE** | ✅ 做(行数计数 + ETA) | `rowsProcessed / totalRows` ticker + 线性外推 ETA |
| **PROCESS / DISPATCH 原子 stage**(SQL / HTTP) | ❌ 不做 | 行数语义不存在;只展示"last heartbeat 8s ago"判活 |
| **统一 0-100% 进度条** | ❌ 不做 | stage 间工作量不均(Preprocess 1s vs Load 10min),条会从 16% 跳 80% 再卡,反而误导 |

## 实现路径(若做)

1. **数据源已有**:V164 `batch.pipeline_progress` 表已经存 `position_marker`(checkpoint resume 用),复用即可
2. **wire**:worker 心跳 body 加 `rowsProcessed` + `totalRowsHint`(2 字段,可空),进 `outbox` 不必,直接走心跳频率(默认 30s)即可
3. **FE**:
   - `FilePipelineObservability` Steps tab 增加列「processed / total」
   - 详情 Drawer 加 sparkline 显示历史 throughput(用同样的心跳序列)
   - ETA 计算:`(totalRows - processed) / 最近 60s 平均 throughput`,< 60s 不显示
4. **降级**:
   - `totalRows` 未知(stream 源 / 未知文件大小)→ 只显示计数,不算 ETA
   - 长时间无心跳(> 3 个间隔)→ 切"无响应"灰态

## 不做的理由

- 强行给 SQL / HTTP atomic stage 加"百分比"= 假进度
- 心跳频率(30s)对秒级 stage 无意义
- stage 级 RUNNING/SUCCESS/FAILED 状态对绝大多数运维场景已够

## 范围边界(避免越界)

- ✅ 流式 stage 内部"已处理多少"
- ❌ pipeline 整体百分比(stage 间权重不可比)
- ❌ "预计 17:42:30 完成"这种精度承诺(throughput 抖动,误导更糟)
- ❌ 业务行级别校验进度(那是数据对账域 ADR-021)

## 优先级

P2(运维体验提升,非阻断)。先做心跳 wire 加 2 字段(BE) + Pipeline Observability 列(FE)两侧 PR,~半天工作量。Sparkline + ETA 视反馈追加。
