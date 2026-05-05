# 批量日与批量运行平台能力设计总结

## 1. 背景与当前实现判断

当前系统已经具备“批量日”相关建模，但还不是完整的“日切驱动型批量运行平台”。

现有实现大致可以分为两层：

### 1.1 调度初始化

服务启动后，`trigger reconciler` 会把 `job_definition` 同步成调度运行态。

默认 wheel 模式会在启动时和每 30 秒进行一次对账，给启用的定时作业生成：

```text
trigger_runtime_state.next_fire_time
```

这一步的含义是：

```text
注册下一次触发时间
```

它不是创建 `job_instance`。

也就是说，当前不是启动后提前把每天所有作业实例都预生成出来。

### 1.2 作业实例初始化

真正的 `job_instance` 是触发发生后才创建的。

触发来源包括：

```text
定时触发
API 触发
手工触发
```

触发后先生成 `LaunchRequest`，再由 orchestrator 的 `DefaultLaunchService.launch()` 落库生成：

```text
job_instance
workflow_run
partition
task
```

所以当前模型是：

```text
调度先注册下一次 fire time
  ↓
触发发生
  ↓
计算 bizDate
  ↓
创建 job_instance / workflow_run / partition / task
```

### 1.3 当前批量日能力

当前系统中已经有批量日相关概念：

```text
business_calendar.cutoff_time
late_arrival_tolerance_min
batch_day_instance
```

默认 cutoff 是：

```text
06:00
```

即 cutoff 前触发的作业归属前一个 `bizDate`。

例如：

```text
2026-05-05 05:30 触发
  ↓
归属 2026-05-04 批量日

2026-05-05 06:30 触发
  ↓
归属 2026-05-05 批量日
```

### 1.4 当前缺口

当前没有平台级默认规则强制：

```text
第二天批量必须等待前一天批量日全部完成后才能启动
```

当前行为更接近：

```text
调度到了就按 fire time 计算 bizDate 并发起
前一天 batch_day_instance 独立 settle
```

如果前一天仍有以下状态实例：

```text
CREATED
WAITING
READY
RUNNING
```

则前一天批量日保持：

```text
IN_FLIGHT
```

如果失败，则按 calendar 的 catch-up 策略进行补跑或等待处理。

因此当前结论是：

```text
有批量日
有 cutoff
有晚到容忍
有结算
有补跑基础
但没有“第二天必须等待前一天”的平台级硬门闩
```

---

## 2. 核心设计判断

### 2.1 日切后应该创建什么

如果系统定位是企业级批量调度平台，批量日不应该只是触发时临时计算出的 `bizDate`。

更合理的是：

```text
日切发生
  ↓
创建 / 打开 batch_day_instance
  ↓
标记该批量日进入 OPEN / IN_FLIGHT
  ↓
当天作业按调度 / API / 手工触发陆续生成 job_instance
  ↓
所有必要作业完成后 settle
  ↓
batch_day_instance = SETTLED
```

也就是说，日切后应该创建的是：

```text
batch_day_instance
```

而不是必须提前创建当天所有 `job_instance`。

推荐模型：

```text
批量日实例：日切后创建
作业实例：触发后创建
```

### 2.2 当前模型的问题

当前模型偏向：

```text
触发驱动的批量日归属
```

即：

```text
触发发生
  ↓
计算 bizDate
  ↓
创建 job_instance
  ↓
挂靠或推动 batch_day_instance
```

这个不是错误，但批量日不够像平台的一等运行对象。

更成熟的模型应该是：

```text
批量日驱动 + 触发驱动结合
```

即：

```text
batch_day_instance 先存在
job_instance 后续按触发挂靠到对应 batch_day_instance 下
```

---

## 3. 第二天是否要等前一天

### 3.1 不能全局写死

第二天是否等待前一天，不能简单做成全平台强制规则。

因为不同类型作业的语义不同。

### 3.2 应该等待的场景

核心日批、账务、清算、余额、库存、对账、状态推进类任务，通常必须等待前一批量日完成。

例如：

```text
T 日清算
T 日对账
T 日余额归集
T 日报表
T+1 依赖 T 日结果
```

这种场景应该要求：

```text
T 日未 SETTLED
T+1 不允许启动关键批量
```

否则会出现：

```text
2026-05-04 批量还没跑完
2026-05-05 批量已经开始
```

这会造成数据口径混乱。

### 3.3 可以并行的场景

独立导入、独立导出、独立文件分发类作业，如果天然按 `bizDate` 分区隔离，并且不会修改同一批状态数据，可以允许不同批量日并行。

例如：

```text
2026-05-04 导出还没完成
2026-05-05 的独立导入可以先跑
```

所以不能写死：

```text
第二天一定要等前一天全部完成
```

### 3.4 推荐结论

推荐支持“可配置等待前一批量日”的能力。

最终原则：

```text
核心日终链路：默认等待前一天结清
独立导入导出：可允许按 bizDate 并行
平台层面：提供策略，不强制全局写死
```

---

## 4. 批量日前后依赖策略

### 4.1 calendar 级别策略

建议在 `business_calendar` 或相关配置表增加：

```text
day_rollover_policy
```

可选值：

```text
ALLOW_OVERLAP
WAIT_PREVIOUS_DAY
REJECT_IF_PREVIOUS_OPEN
```

含义：

| 策略 | 含义 |
|---|---|
| `ALLOW_OVERLAP` | 允许今天和昨天批量日重叠执行 |
| `WAIT_PREVIOUS_DAY` | 今天触发了，但前一天没结清，则进入等待 |
| `REJECT_IF_PREVIOUS_OPEN` | 今天触发了，但前一天没结清，直接拒绝启动 |

推荐执行逻辑：

```text
06:00 日切
  ↓
创建 2026-05-05 batch_day_instance
  ↓
检查 2026-05-04 是否 SETTLED / SKIPPED

如果策略 = ALLOW_OVERLAP：
    允许 2026-05-05 作业启动

如果策略 = WAIT_PREVIOUS_DAY：
    2026-05-05 作业进入 BLOCKED / WAITING_PREVIOUS_DAY
    等 2026-05-04 SETTLED 后自动释放

如果策略 = REJECT_IF_PREVIOUS_OPEN：
    本次触发拒绝，记录 launch_rejected / audit_log
```

### 4.2 job 级别策略

有些作业需要覆盖 calendar 默认策略。

建议在 `job_definition` 增加：

```text
previous_day_dependency_scope
```

可选值：

```text
NONE
SAME_JOB
SAME_JOB_GROUP
SAME_CALENDAR
CUSTOM_CHAIN
```

含义：

| 策略 | 含义 |
|---|---|
| `NONE` | 不等待前一天 |
| `SAME_JOB` | 只等待当前 job 的前一天完成 |
| `SAME_JOB_GROUP` | 等同一批量组前一天完成 |
| `SAME_CALENDAR` | 等整个 calendar 的前一天结清 |
| `CUSTOM_CHAIN` | 自定义跨日依赖链 |

示例：

| 作业类型 | 推荐策略 |
|---|---|
| 账务日终总控 | `SAME_CALENDAR` |
| 普通报表 | `SAME_JOB` |
| 独立文件导入 | `NONE` |
| T+1 清算 | `CUSTOM_CHAIN` |
| 客户维度对账 | `SAME_JOB_GROUP` 或 `CUSTOM_CHAIN` |

### 4.3 DAG / pipeline 级别依赖

对于复杂工作流，不应只依赖隐式批量日规则，而应显式建模 DAG 依赖。

例如：

```text
daily-close-20260504 SETTLED
  ↓
daily-open-20260505
```

或者：

```text
T 日结算完成节点
  ↓
T+1 作业节点
```

这样依赖关系更清晰，也便于排障。

---

## 5. 批量日生命周期能力

批量日应该成为平台的一等运行对象。

建议完整生命周期包括：

```text
批量日打开
批量日运行中
批量日冻结
批量日结算
批量日关闭
批量日重开
批量日跳过
批量日补跑
```

推荐状态：

```text
CREATED
OPEN
IN_FLIGHT
FROZEN
SETTLING
SETTLED
FAILED
SKIPPED
REOPENED
BLOCKED
```

状态说明：

| 状态 | 含义 |
|---|---|
| `CREATED` | 批量日实例已创建 |
| `OPEN` | 批量日已打开，可以接受作业触发 |
| `IN_FLIGHT` | 批量日存在运行中作业 |
| `FROZEN` | 批量日被冻结，暂停新作业启动 |
| `SETTLING` | 批量日正在结算 |
| `SETTLED` | 批量日已正常结清 |
| `FAILED` | 批量日失败，需要处理 |
| `SKIPPED` | 批量日被跳过 |
| `REOPENED` | 批量日已重开 |
| `BLOCKED` | 批量日被前置条件阻塞 |

其中，允许后续批量日继续的终态建议为：

```text
SETTLED
SKIPPED
```

不建议自动允许后续继续的状态：

```text
FAILED
IN_FLIGHT
BLOCKED
FROZEN
```

除非人工强制放行。

---

## 6. 补跑能力

补跑不是简单重新执行一次，而是一套独立的运行治理能力。

### 6.1 补跑粒度

建议支持：

| 能力 | 说明 |
|---|---|
| 单作业补跑 | 只补某个 job |
| 整批量日补跑 | 补某个 bizDate 下所有必要作业 |
| 区间补跑 | 补一段日期，如 2026-05-01 到 2026-05-05 |
| 失败重跑 | 只重跑失败实例 |
| 跳过后补跑 | 原来 SKIPPED，后续业务要求补跑 |
| 分区补跑 | 只补某个 partition |
| step 补跑 | 只补某个 step |

### 6.2 补跑模式

建议支持：

```text
RERUN_FAILED_ONLY
RERUN_ALL
RERUN_FROM_STEP
RERUN_PARTITION
RERUN_BATCH_DAY
RERUN_DATE_RANGE
```

### 6.3 结果覆盖策略

补跑要明确结果如何处理：

```text
OVERWRITE_RESULT
CREATE_NEW_VERSION
KEEP_BOTH
MANUAL_CONFIRM_EFFECTIVE
```

不建议直接覆盖原执行记录。

推荐生成新的 instance，并记录来源关系：

```text
original_job_instance_id
rerun_job_instance_id
```

### 6.4 补跑关键字段

建议记录：

```text
rerun_type
rerun_reason
rerun_by
source_instance_id
rerun_policy
result_overwrite_policy
config_version_policy
created_time
```

### 6.5 catch-up 策略

漏批后的 catch-up 不应该只有自动补跑。

建议支持：

```text
AUTO_RUN_MISSED
MANUAL_CONFIRM
SKIP_MISSED
BLOCK_UNTIL_RESOLVED
```

含义：

| 策略 | 含义 |
|---|---|
| `AUTO_RUN_MISSED` | 自动补跑漏批 |
| `MANUAL_CONFIRM` | 人工确认补跑还是跳过 |
| `SKIP_MISSED` | 自动跳过漏批 |
| `BLOCK_UNTIL_RESOLVED` | 阻塞直到人工处理 |

---

## 7. 跳批能力

### 7.1 跳批定义

跳批不是简单不跑，而是：

```text
有记录地跳过
```

例如：

```text
2026-05-04 这个批量日不跑
```

平台不能只是“不生成实例”，而应保留明确记录：

```text
batch_day_instance = SKIPPED
skip_reason = HOLIDAY / NO_FILE / MANUAL_APPROVED / BUSINESS_NOT_REQUIRED
skip_by = operator
skip_time = xxx
```

这样后续审计、依赖判断、补跑判断、批量日结算才有依据。

### 7.2 跳过粒度

建议支持四层：

```text
BATCH_DAY
JOB
STEP
PARTITION
```

| 粒度 | 场景 |
|---|---|
| `BATCH_DAY` | 节假日、业务停批、迁移窗口、整体跳过 |
| `JOB` | 某个文件当天没有、某个报表当天不用出 |
| `STEP` | 某个处理步骤当天无需执行 |
| `PARTITION` | 某个客户、地区、分片当天无数据 |

### 7.3 跳批后对后续依赖的影响

跳过后，依赖怎么处理不能写死。

建议提供策略：

```text
TREAT_AS_SUCCESS
BLOCK_DOWNSTREAM
SKIP_DOWNSTREAM
REQUIRE_MANUAL_RELEASE
```

含义：

| 策略 | 含义 |
|---|---|
| `TREAT_AS_SUCCESS` | 跳过视为成功，下游可继续 |
| `BLOCK_DOWNSTREAM` | 阻塞下游 |
| `SKIP_DOWNSTREAM` | 下游也跳过 |
| `REQUIRE_MANUAL_RELEASE` | 需要人工放行 |

示例：

```text
节假日文件导入跳过
  ↓
下游报表也跳过
```

可以配置为：

```text
SKIP_DOWNSTREAM
```

账务核心作业被跳过，通常不能让下游自动继续，应配置为：

```text
REQUIRE_MANUAL_RELEASE
```

### 7.4 跳批审计字段

建议记录：

```text
skip_scope
calendar_code
biz_date
job_code
step_code
partition_key
skip_reason
skip_comment
operator
approved_by
created_time
effective_time
dependency_policy
```

### 7.5 跳批与补跑关系

补跑解决：

```text
漏了要补
```

跳批解决：

```text
确认不用跑，但不能卡住后面
```

所以跳批和补跑必须协同。

常见决策：

```text
发现 2026-05-04 未跑
  ↓
补跑 2026-05-04
或跳过 2026-05-04
或阻塞 2026-05-05 等待人工处理
```

---

## 8. 人工干预能力

批量平台必须支持人工干预，否则生产异常时只能改数据库。

### 8.1 必备操作

建议支持：

| 操作 | 说明 |
|---|---|
| 暂停作业 | 禁止新的触发 |
| 恢复作业 | 重新允许触发 |
| 终止实例 | 停止正在运行的实例 |
| 标记成功 | 人工确认已完成 |
| 标记失败 | 人工确认失败 |
| 跳过实例 | 不跑但记账 |
| 释放等待 | 前置条件人工确认后放行 |
| 重新调度 | 修改 next_fire_time |
| 重开批量日 | 已结算批量日重新处理 |
| 冻结批量日 | 暂停该批量日新任务启动 |
| 关闭批量日 | 不再接收新的作业触发 |

### 8.2 审计要求

人工操作必须全部进入审计表。

建议表：

```text
batch_operation_audit
```

字段建议：

```text
operation_type
target_type
target_id
before_status
after_status
operator
reason
approval_id
created_time
request_payload
```

### 8.3 高风险操作审批

高风险操作建议走审批：

```text
跳批
补跑
人工置成功
人工释放
重开批量日
修改调度时间
禁用作业
```

普通低风险操作可配置免审批。

---

## 9. 依赖与 DAG 能力

批量系统不能只靠时间触发，还需要显式依赖能力。

### 9.1 依赖类型

建议支持：

```text
作业依赖
步骤依赖
跨批量日依赖
跨日历依赖
文件依赖
外部系统依赖
人工确认依赖
```

### 9.2 依赖条件

建议支持：

```text
SUCCESS
SETTLED
FILE_ARRIVED
FILE_VALIDATED
MANUAL_APPROVED
EXTERNAL_SIGNAL
SKIPPED_ALLOWED
```

### 9.3 依赖失败策略

建议支持：

```text
BLOCK
SKIP
FAIL_FAST
CONTINUE_WITH_WARNING
REQUIRE_MANUAL_RELEASE
```

### 9.4 示例链路

```text
文件到达
  ↓
导入
  ↓
校验
  ↓
对账
  ↓
导出
  ↓
分发
  ↓
批量日结算
```

---

## 10. 文件批量能力

对于导入、导出、分发平台，文件能力必须是一等能力。

### 10.1 文件到达与稳定性

建议支持：

| 能力 | 说明 |
|---|---|
| 文件到达检测 | 判断文件是否按时到达 |
| 半文件保护 | 防止文件未传完就被处理 |
| 多文件等待 | 一组文件全部到齐再启动 |
| 文件晚到处理 | 超过 SLA 后告警、等待、跳过或失败 |
| 空文件策略 | 允许、拒绝、跳过、告警 |
| 重复文件策略 | 覆盖、拒绝、版本化 |

### 10.2 文件校验

建议支持：

```text
文件名校验
文件大小校验
行数校验
hash 校验
编码校验
字段结构校验
业务规则校验
```

### 10.3 文件处理后动作

建议支持：

```text
归档
备份
错误文件落盘
错误行 sidecar 文件
处理结果回写
文件血缘记录
```

### 10.4 文件状态

推荐状态：

```text
WAITING
ARRIVED
STABLE
VALIDATED
CONSUMED
ARCHIVED
FAILED
SKIPPED
```

---

## 11. 幂等与重入能力

幂等是批量平台的核心能力。

系统必须明确：

```text
同一个 job_code + biz_date + trigger_type + partition_key 是否允许重复启动？
```

### 11.1 推荐幂等策略

```text
REJECT_DUPLICATE
RETURN_EXISTING
CREATE_NEW_VERSION
ALLOW_PARALLEL
```

含义：

| 策略 | 含义 |
|---|---|
| `REJECT_DUPLICATE` | 重复启动直接拒绝 |
| `RETURN_EXISTING` | 返回已有实例 |
| `CREATE_NEW_VERSION` | 创建新版本运行 |
| `ALLOW_PARALLEL` | 允许并行运行 |

### 11.2 不同作业推荐

| 作业类型 | 推荐策略 |
|---|---|
| 导入类 | `REJECT_DUPLICATE` 或 `CREATE_NEW_VERSION` |
| 导出类 | `CREATE_NEW_VERSION` |
| 分发类 | 谨慎，通常避免重复发送 |
| 账务类 | 强幂等，禁止并行重复 |

### 11.3 推荐唯一键维度

```text
tenant_id
calendar_code
job_code
biz_date
batch_no
partition_key
run_version
```

---

## 12. 结果版本与回滚能力

如果支持补跑，就必须处理结果版本问题。

问题示例：

```text
2026-05-04 的报表已经导出过一次
后来补跑又导出了一版
到底哪版有效？
```

建议支持：

| 能力 | 说明 |
|---|---|
| `run_version` | 第几次执行 |
| `effective_version` | 当前有效版本 |
| `result_snapshot` | 结果快照 |
| `rollback` | 回退到上一版本 |
| `supersede` | 新结果替代旧结果 |
| `compare` | 对比两次结果差异 |

补跑时建议支持：

```text
新旧结果都保留
人工确认哪版生效
保留版本血缘
允许回滚
```

---

## 13. 配置版本治理能力

批量配置不能随便改，否则补跑历史日期时会出现口径问题。

例如：

```text
补跑 2026-05-04
到底用 2026-05-04 当时的配置，还是用今天的新配置？
```

### 13.1 配置治理能力

建议支持：

```text
草稿
发布
版本
审批
灰度
回滚
生效时间
配置 diff
发布前校验
```

### 13.2 实例绑定配置版本

运行实例应绑定：

```text
job_config_version
pipeline_config_version
calendar_version
file_template_version
```

### 13.3 补跑配置策略

补跑时可选：

```text
USE_ORIGINAL_CONFIG
USE_LATEST_CONFIG
USE_SPECIFIED_VERSION
```

---

## 14. SLA 与告警能力

批量平台不能只看成功失败，还要看是否按时。

### 14.1 作业 SLA

建议字段：

```text
expected_start_time
latest_start_time
expected_finish_time
latest_finish_time
late_arrival_tolerance
```

### 14.2 批量日 SLA

建议字段：

```text
batch_day_expected_settle_time
batch_day_latest_settle_time
```

### 14.3 告警类型

建议支持：

```text
未开始
运行超时
文件未到
文件晚到
依赖长时间等待
失败次数超限
补跑失败
批量日未按时结算
worker 离线
队列堆积
```

---

## 15. 限流与容量治理能力

平台中存在导入、导出、分发 worker，后续一定会遇到资源抢占。

### 15.1 资源治理维度

建议支持：

| 能力 | 说明 |
|---|---|
| workerType 路由 | import/export/dispatch 分开 |
| capabilityTags | 按能力标签选择 worker |
| resourceProfile | 声明 CPU、内存、IO、并发需求 |
| 队列限流 | 防止瞬间打爆 worker |
| 外部系统限流 | 防止打爆数据库、SFTP、API |
| 同作业并发限制 | 同一个 job 同时最多几个实例 |
| 同租户并发限制 | 防止某个租户占满资源 |
| 优先级 | 日终核心链路优先 |
| 降级 | 非核心任务延后 |

### 15.2 限流范围

建议支持：

```text
按 workerType 限流
按 job_code 限流
按 tenant 限流
按外部目标系统限流
按队列限流
按业务域限流
```

分发时限流是正确方向，但还需要扩展到多维度资源治理。

---

## 16. 失败处理能力

失败不应该只有一个 `FAILED`。

### 16.1 失败分类

建议细分：

```text
业务失败
技术失败
资源不足
依赖失败
超时失败
人工终止
数据校验失败
外部系统失败
幂等冲突
文件缺失
```

### 16.2 失败后策略

建议支持：

```text
自动重试
延迟重试
指数退避
转人工
跳过
补偿
进入死信
阻塞后续
```

### 16.3 状态建议

```text
FAILED_RETRYABLE
FAILED_NON_RETRYABLE
WAITING_RETRY
EXHAUSTED
MANUAL_REQUIRED
```

---

## 17. 死信与补偿能力

只要平台使用 Kafka / 队列，就需要死信能力。

### 17.1 死信类型

建议支持：

```text
task_dead_letter
dispatch_dead_letter
file_dead_letter
webhook_dead_letter
```

### 17.2 死信操作

建议支持：

```text
查看死信
重放死信
丢弃死信
修改参数后重放
按实例重放
按批量日重放
```

重放必须和幂等能力结合，否则容易出现：

```text
重复导入
重复导出
重复分发
```

---

## 18. 观测与审计能力

平台至少要回答这些问题：

```text
今天批量跑到哪了？
哪个作业卡住了？
为什么卡住？
等哪个依赖？
哪个文件没到？
哪个 worker 在跑？
失败原因是什么？
有没有补跑？
有没有人工跳过？
结果文件在哪里？
```

建议视图：

| 视图 | 作用 |
|---|---|
| 批量日视图 | 看某天整体状态 |
| 作业实例视图 | 看每个 job 的状态 |
| DAG 视图 | 看依赖和阻塞点 |
| 文件视图 | 看文件到达和处理 |
| worker 视图 | 看执行节点负载 |
| 补跑视图 | 看补跑链路 |
| 审计视图 | 看人工操作 |
| 死信视图 | 看失败消息和重放 |

---

## 19. 权限与审批能力

批量平台中的很多操作是高风险操作。

### 19.1 权限点建议

```text
batch_day.skip
batch_day.reopen
batch_day.freeze
batch_day.release
job.rerun
job.force_success
job.terminate
schedule.modify
config.publish
file.reprocess
dead_letter.replay
```

### 19.2 高风险审批

建议以下操作走审批：

```text
跳批量日
跳核心作业
人工置成功
人工释放
重开批量日
补跑账务类作业
修改生产调度时间
发布核心配置
```

---

## 20. 多租户与多业务线能力

如果后续要平台化，建议提前预留：

```text
tenant_id
app_code
business_domain
calendar_code
job_group
owner_team
```

这些字段会影响：

```text
权限
隔离
告警
报表
资源限流
审计
成本统计
```

---

## 21. 推荐能力优先级

### 21.1 P0：必须优先补齐

```text
批量日实例日切创建
前一批量日等待策略
补跑
跳批
人工释放
幂等控制
失败重试
审计日志
SLA 告警
文件到达等待
```

### 21.2 P1：强烈建议

```text
批量日冻结 / 关闭 / 重开
跨日依赖
配置版本
结果版本
死信重放
资源限流
worker 路由
DAG 阻塞分析
```

### 21.3 P2：平台成熟后增强

```text
审批流
灰度发布
容量预测
自动优化调度
智能异常诊断
历史运行画像
跨系统血缘
成本统计
```

---

## 22. 推荐最终运行闭环

完整批量运行平台应该形成以下闭环：

```text
日切建批量日
  ↓
判断前一日是否可继续
  ↓
按调度 / API / 手工触发作业
  ↓
检查依赖、文件、资源、幂等
  ↓
执行 DAG / step / partition
  ↓
失败重试、跳过、补跑、人工释放
  ↓
批量日结算
  ↓
审计、告警、归档、结果版本化
```

---

## 23. 推荐落地顺序

### 阶段 1：补齐批量日主线

目标：让批量日成为一等运行对象。

建议改造：

```text
1. 日切后主动创建 batch_day_instance
2. 增加 batch_day_instance 状态机
3. 增加前一批量日等待策略
4. 增加 batch_day settle 判断
5. 增加批量日视图
```

### 阶段 2：补齐补跑 / 跳批 / 人工释放

目标：生产异常不再靠改库处理。

建议改造：

```text
1. 支持 batch_day / job / step / partition 跳过
2. 支持单作业补跑、失败补跑、批量日补跑
3. 支持人工释放等待
4. 增加 operation_audit
5. 补跑和跳批联动依赖判断
```

### 阶段 3：补齐依赖与文件治理

目标：让导入、导出、分发成为稳定 pipeline。

建议改造：

```text
1. 文件到达等待
2. 半文件保护
3. 多文件组等待
4. 文件晚到策略
5. DAG 阻塞原因分析
```

### 阶段 4：补齐版本、审计、SLA、资源治理

目标：达到企业级运维能力。

建议改造：

```text
1. 配置版本
2. 结果版本
3. SLA 告警
4. 多维限流
5. 死信重放
6. 权限审批
```

---

## 24. 最终结论

当前系统已经具备批量日相关骨架，但仍偏向“触发后归属批量日”的模型。

建议升级为：

```text
日切驱动的批量日模型
+
触发驱动的作业实例模型
```

核心结论：

```text
1. 日切后应该创建 batch_day_instance，而不是必须预生成所有 job_instance。
2. job_instance 仍然建议在定时 / API / 手工触发后按需创建。
3. 第二天是否等待前一天不能全局写死，应策略化配置。
4. 核心日终、账务、清算类作业应默认等待前一批量日结清。
5. 独立导入、导出、分发作业可按 bizDate 隔离后允许并行。
6. 平台必须支持补跑、跳批、人工释放、批量日重开。
7. 跳批必须有记录、有原因、有审计，并能参与依赖判断。
8. 补跑必须有来源实例、版本策略和结果有效性管理。
9. 批量平台最终要形成日切、触发、依赖、执行、补偿、结算、审计的完整闭环。
```

一句话总结：

```text
补跑解决“该跑但没跑或失败了”的问题；
跳批解决“确认不用跑但不能卡住后续”的问题；
前一批量日等待解决“跨日数据口径一致性”的问题；
批量日生命周期解决“平台级运行治理”的问题。
```
