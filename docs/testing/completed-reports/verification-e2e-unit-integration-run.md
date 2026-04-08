# 逐类验证报告（Unit / Integration / E2E）

日期：2026-03-29  
目标：逐一验证所有测试类（尽量避免重复跑已成功用例），并把失败与修复过程记录下来。  
fail-fast：关闭（`-Dsurefire.failFast=false`、`-Djunit.jupiter.execution.fail_fast=false`）  
端口：统一设置 `-Dserver.port=0`（尽量避免占用）

---

## 预先修复（为通过之前 E2E 报错）

1. `DispatchPipelineE2eIT` 初次失败：`Unrecognized field "run_mode"`  
   - 修复：在 `batch-worker-dispatch` 的 `DispatchPayload` 增加 `run_mode` 字段兼容（`@JsonProperty("run_mode")` / `@JsonAlias("runMode")`）。
2. `DispatchPipelineE2eIT` 后续失败：`ON CONFLICT (tenant_id, event_key)` 无匹配唯一约束  
   - 根因（历史）：当时 `platform-init.sql` 手写了 `outbox_event` 表且与 Flyway 不一致。  
   - 现状：`platform-init.sql` 仅建 schema；`outbox_event` 及唯一约束完全由 Flyway 迁移定义。
3. `ExportPipelineE2eIT` 失败：`Unrecognized field "run_mode"`  
   - 修复：在 `batch-worker-export` 的 `ExportPayload` 增加 `runMode`（`@JsonProperty("run_mode")` / `@JsonAlias("runMode")`），并同步更新 `batch-worker-export` 内相关单测里 `new ExportPayload(...)` 的入参（在 `autoDispatch` 后补 `null`）。

验证结果：
- ✅ `DispatchPipelineE2eIT`：通过

---

## E2E 测试类验证结果（逐个跑）

> 说明：每个条目只在“该类失败”时才会额外记录修复与重跑；其余类仅记录通过/失败。

| 测试类 | 命令（摘要） | 结果 | 备注 |
|---|---|---|---|
| `com.example.batch.e2e.DispatchPipelineE2eIT` | `mvn test -pl batch-e2e-tests -am -Dtest=DispatchPipelineE2eIT ...` | PASS | 见上方预先修复 |
| `com.example.batch.e2e.ImportPipelineE2eIT` | `mvn test -pl batch-e2e-tests -am -Dtest=ImportPipelineE2eIT ...` | FAIL | 当前错误：`customerNo is required`；前序修复已处理 `run_mode`、`ON CONFLICT`、JSON 解析兼容/宽松转换，但解析出来行仍缺 `customerNo`（继续定位 task_payload/content 形态） |
| `com.example.batch.e2e.ExportPipelineE2eIT` | `mvn test -pl batch-e2e-tests -am -Dtest=ExportPipelineE2eIT ...` | PASS | 补齐 `ExportPayload` 的 `run_mode` 兼容字段后通过 |

---

## 单元测试 / 集成测试

（待执行并回填）

