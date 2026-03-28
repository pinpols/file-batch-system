# Phase 2：P0 功能回归补齐

更新时间：2026-03-28

## 目标

本文件用于保留 Phase 2 的 P0 收口清单和后续增量项，便于单独查阅和维护。

## 已完成的首轮收口

### `batch-trigger` 首批门禁

已落地：

- `DefaultTriggerServiceTest`
- `DefaultLaunchAdapterServiceTest`
- `QuartzLaunchJobTest`
- `TriggerSchedulerFacadeTest`
- `TriggerControllerTest`
- `TriggerServiceIntegrationIT`

覆盖点：

- API 请求校验、缺少幂等键、自动生成 `requestId/traceId`
- trigger request 幂等落库与重复提交去重
- catch-up 审批通过后的状态推进
- Quartz misfire 后的自动补跑 / 人工审批分支
- Quartz 注册时 `catchUpPolicy` / `catchUpMaxDays` 元数据透传
- `batch-trigger` 与真实 PostgreSQL 的基本协作

### 接口层校验补强

已修正：

- `TriggerLaunchRequest` 补齐字段校验注解
- `TriggerApiExceptionHandler` 补齐 `MethodArgumentNotValidException`
- `TriggerApiExceptionHandler` 统一处理 `ConstraintViolationException` 和兜底 500

### Quartz catch-up 策略修正

已修正：

- `TriggerSchedulerFacade` 注册任务时补充 `calendarCode`、`catchUpPolicy`、`catchUpMaxDays`
- `QuartzLaunchJob` 执行时恢复这些元数据并参与 misfire 决策

### 统一回归入口

已落地：

- `scripts/ci/run-full-regression.sh`

当前脚本能力：

- reactor 默认测试（`*Test` / `*IntegrationTest`）
- reactor 显式 `*IT` 套件，包含需要单独触发的集成测试和 E2E
- 可选 Gatling load smoke
- 可选 Helm deploy smoke
- 可选巡检脚本收尾
- 可选 live deploy smoke：`helm upgrade --install --wait` + rollout + readiness

## 仍建议继续补强的 P0

- `batch-worker-core` 真实 listener / backpressure / drain 协作测试
- dedup key 幂等冲突专项
- Kafka 重复投递与重试耗尽专项
- 外部渠道失败与恢复专项

## 本轮新增补强

- `batch-console-api` 权限/租户负向测试
- 同 task 并发 claim 竞争专项
- Kafka 外部渠道失败与恢复专项（补了 `KafkaOutboxPublisher` 失败分支）

## 补充说明

- `batch-worker-core` 已有 backpressure、worker loop、lease/wrapper 的单测基础，仍可继续补真实 listener 级协作测
- `dedup key` 和 retry exhausted 已有 service/mapper 级覆盖，后续若继续扩展，优先补 E2E 或真实依赖链路
