# Workflow 动态 fan-out(P1, v0.1)

> 2026-05-30。对标 Airflow dynamic task mapping:一个 workflow TASK 节点按上游节点的 output 数组,
> 在运行时展开成 N 个并行执行单元。

## 设计选择:复用 partition 机制(不另造状态机)

项目已有一套久经考验的"一个 job_instance → N 个 job_partition 并行 → N 个 partition 终态聚合成
instance 终态"的机制(乐观锁 + ADR-014 防迟到覆盖)。动态 fan-out **直接复用它**:fan-out 节点是
TASK 节点,它的 N 个并行单元就是 N 个 job_partition,聚合(N 完成 → 节点终态)走现有逻辑,**不新增
状态机、不新增表、不改聚合**。这是三条路径里风险最低的。

- ❌ 不走 run_seq(那是"重跑第 N 次"语义,借用会跟 nextRunSeq 自增冲突)
- ❌ 不在 node 层新造并行聚合状态机(量大、风险高)

## 配置(workflow_node.node_params)

```json
{
  "fanOut": {
    "itemsExpr": "$.nodes.SPLIT.output.shards",   // 必填:解析成数组的 DSL 引用
    "itemParam": "shard",                          // 可选:注入到每个分区 payload 的 key,默认 fanOutItem
    "maxFanOut": 100                               // 可选:上限,默认 200
  }
}
```

## 运行时行为

派发该 TASK 节点时(`DefaultWorkflowNodeDispatchService.dispatchTaskNode`):

1. `WorkflowFanOutSupport.parseSpec` 读 fanOut 配置(无则普通节点,行为不变)。
2. `WorkflowNodePayloadBuilder.resolveFanOutItems` 用 workflow_run 上下文把 `itemsExpr` 解析成
   数组(复用 ADR-009 的 `$.nodes.<X>.output.<key>` 解析)。
3. 校验:空数组 → fail-fast(v0.1,上游须保证非空或用 GATEWAY 守护);超 maxFanOut → fail-fast。
4. 把 SchedulePlan 的分区展开成 N 份(在资源调度前,N 个分区都参与 worker 路由)。
5. 每个分区的 task payload = 基础 payload + `{ <itemParam>: item[i], fanOutIndex: i, fanOutTotal: N }`。
   worker 据此知道自己处理哪一份。
6. N 个 partition 走现有派发 + 聚合 → 全部终态 → 节点终态 → DAG 继续。

## 分区计划契约

`SchedulePlan.PartitionPlan` 会在派发前固化以下字段，并写入 `job_partition.input_snapshot`:

- `partitionPlanVersion`: 当前为 `1`
- `partitionNo`: 平台内 1-based 分区序号
- `shardIndex`: worker 侧 0-based 分片下标
- `shardTotal`: 本次 fan-out / 分片总数
- `rangeStartInclusive` / `rangeEndExclusive`: 半开范围，仅当计划携带 `expectedRows/totalExpectedRows/totalRowsHint/recordCount/estimatedItemCount` 时填充
- `expectedRows`: 本分片预期行数；无总量提示时为 `null`

这个契约是 worker 可读、运维可审计、重放可复现的最小分区计划，不表达业务切分字段本身。业务如何按行号、文件 offset、机构号或对象 key 切分，仍由对应 worker/plugin 解释 payload 与 partition snapshot。

## v0.1 边界 / 不做

- **空数组 fail-fast**(非直接 SUCCESS):简单 + 低风险。v0.2 再支持空 fan-out 合成 SUCCESS node_run。
- **同 worker 路由**:N 个分区克隆首个分区的 route 模板,靠资源调度 + dispatch 时再均衡。
- **无嵌套 fan-out**:一个 fan-out 节点不再展开二级。
- **结果收集(fan-in)**:下游通过现有 partition output 聚合 / nodeOutput 拿结果;没有专门的
  "collect N 个 fan-out 产出成一个数组"语义,需要时 v0.2 加。

## 守护 / 测试

- `WorkflowFanOutSupportTest`(7):配置解析(默认值 / 自定义 / null 分支)、分区展开、item 注入。
- 现有 39 个 workflow dispatch / DAG 测试无回归(非 fan-out 节点 payload 行为完全不变)。
- i18n:`error.workflow.fan_out_*` en + zh 1:1。
