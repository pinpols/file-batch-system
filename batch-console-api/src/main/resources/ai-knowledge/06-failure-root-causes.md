# 失败分类(failureClass)→ 常见根因与排查

实例/任务失败会被 orchestrator 侧 `FailureClassifier` 归一到一个 `failureClass`(枚举 `FailureClass`),
它只是**粗分类信号**,不是最终结论;定位真实 root cause 仍要看执行日志。用 `getJobInstance` 拿到某实例的
`failureClass`,再用 `getJobExecutionLogs` 看该实例的具体报错;或用 `listRecentFailedJobInstances` 找出近期要排查的实例 id。

## INFRASTRUCTURE —— 基础设施异常(瞬态,自动重试候选)
- 典型:DB 连接/网络抖动、Kafka 不可用、PG SQLState 08xxx(连接) / 40001 序列化失败 / 40P01 死锁 / 53xxx 资源不足 / 57/58xxx 系统错误。
- 排查:看日志里的连接/超时/死锁字样;确认下游依赖(PG、Kafka、对象存储)是否健康;这类通常由重试机制自愈,反复失败才需人工。
- 建议:先看是否已在重试;若耗尽重试,定位依赖故障后再 RERUN。

## DATA_QUALITY —— 数据质量异常(不可重试,需业务方介入)
- 典型:唯一键冲突、CHECK/NOT NULL/外键违反(SQLState 23xxx)、数据转换/越界(22xxx)。
- 排查:用 `getJobExecutionLogs` 看是哪行/哪列/哪个约束;这类**重试无用**,是源数据或映射问题。
- 建议:修数据或修映射规则后 RERUN,不要盲目重试。

## BUSINESS_RULE —— 业务规则异常(跳过或人工裁决)
- 典型:业务校验主动拒绝(如金额不平、状态不允许)。
- 排查:看日志中的业务校验信息;判断是跳过该记录还是需人工裁决。

## CONFIG —— 配置异常(ops 修配置后 RERUN)
- 典型:SQL 语法/权限(SQLState 42xxx)、渠道 endpoint/白名单错、job_definition 配置不当。
- 排查:核对相关配置(job 定义、渠道配置、模板);SSRF 防护拦截解析到内网的目标不是 bug。
- 建议:修配置后 RERUN。

## UPSTREAM_DELAY —— 上游延迟(等待 / WAITING_DEPENDENCY)
- 典型:依赖的上游数据/文件未就绪,实例在等待。
- 排查:确认上游产出是否到达;是否触发条件未满足。

## TIMEOUT —— 超时(上下文相关)
- 典型:query timeout / Kafka send timeout / HTTP read timeout;或任务执行超过 deadline。
- 排查:看是哪一步超时;是数据量大、下游慢,还是 worker 卡住(结合集群诊断 getClusterDiagnostics 看 worker 一致性)。

## UNKNOWN —— 未分类(ops 人工判定信号,不是 bug)
- 含义:分类器无法归类,**需要人工看日志判定**,不代表系统出错。
- 排查:直接看 `getJobExecutionLogs` 原始报错。

## 排查动线(推荐顺序)
1. `listRecentFailedJobInstances` → 找到要查的实例 id。
2. `getJobInstance(id)` → 看 status / failureClass / resultSummary。
3. `getJobExecutionLogs(id)` → 看具体报错定位 root cause。
4. 若疑似集群面卡点(不推进/无 worker/事件积压)→ `getClusterDiagnostics`。
5. 输出**受控处置建议**(RERUN / 修数据 / 修配置 / 等上游),不代执行、不写库。
