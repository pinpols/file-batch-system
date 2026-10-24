# SIM 共享场景数据

本目录为脚本与多模块测试共用的 SQL / JSON；使用规则见 [测试数据指南](../../docs/testing/test-fixture-guide.md)。初始化、失败注入与回读通过对应 scripts/sim 场景入口执行。

| 文件 | 用途 |
|---|---|
| [sim-bundle-import-fixtures.sql](sim-bundle-import-fixtures.sql) | SIM bundle import fixtures 场景 SQL |
| [sim-e2e-bootstrap.sql](sim-e2e-bootstrap.sql) | SIM e2e bootstrap 场景 SQL |
| [sim-import-tenants-verify.sql](sim-import-tenants-verify.sql) | SIM import tenants verify 场景 SQL |
| [sim-stage2b-import-fixtures.sql](sim-stage2b-import-fixtures.sql) | SIM stage2b import fixtures 场景 SQL |
| [sim-stage2c-import-matrix-business.sql](sim-stage2c-import-matrix-business.sql) | SIM stage2c import matrix business 场景 SQL |
| [sim-stage2c-import-matrix-fixtures.sql](sim-stage2c-import-matrix-fixtures.sql) | SIM stage2c import matrix fixtures 场景 SQL |
| [sim-stage2c-import-matrix-stale.sql](sim-stage2c-import-matrix-stale.sql) | SIM stage2c import matrix stale 场景 SQL |
| [sim-stage2d-reset-errors.sql](sim-stage2d-reset-errors.sql) | SIM stage2d reset errors 场景 SQL |
| [sim-stage2e-import-checkpoint-business.sql](sim-stage2e-import-checkpoint-business.sql) | SIM stage2e import checkpoint business 场景 SQL |
| [sim-stage2e-import-checkpoint.sql](sim-stage2e-import-checkpoint.sql) | SIM stage2e import checkpoint 场景 SQL |
| [sim-stage3-export-source.sql](sim-stage3-export-source.sql) | SIM stage3 export source 场景 SQL |
| [sim-stage3b-export-fixtures.sql](sim-stage3b-export-fixtures.sql) | SIM stage3b export fixtures 场景 SQL |
| [sim-stage3b-export-source.sql](sim-stage3b-export-source.sql) | SIM stage3b export source 场景 SQL |
| [sim-stage3c-export-source.sql](sim-stage3c-export-source.sql) | SIM stage3c export source 场景 SQL |
| [sim-stage4-process-business-fixtures.sql](sim-stage4-process-business-fixtures.sql) | SIM stage4 process business fixtures 场景 SQL |
| [sim-stage4-process-platform-fixtures.sql](sim-stage4-process-platform-fixtures.sql) | SIM stage4 process platform fixtures 场景 SQL |
| [sim-stage4b-process-source-v1.sql](sim-stage4b-process-source-v1.sql) | SIM stage4b process source v1 场景 SQL |
| [sim-stage4b-process-source-v2.sql](sim-stage4b-process-source-v2.sql) | SIM stage4b process source v2 场景 SQL |
| [sim-stage4b-process-stale-staging.sql](sim-stage4b-process-stale-staging.sql) | SIM stage4b process stale staging 场景 SQL |
| [sim-stage4c-process-business.sql](sim-stage4c-process-business.sql) | SIM stage4c process business 场景 SQL |
| [sim-stage4c-process-platform.sql](sim-stage4c-process-platform.sql) | SIM stage4c process platform 场景 SQL |
| [sim-stage5-dispatch-file.sql](sim-stage5-dispatch-file.sql) | SIM stage5 dispatch file 场景 SQL |
| [sim-stage5b-dispatch-fixtures.sql](sim-stage5b-dispatch-fixtures.sql) | SIM stage5b dispatch fixtures 场景 SQL |
| [sim-stage5c-atomic-params.json](sim-stage5c-atomic-params.json) | ATOMIC 参数 fixture |
| [sim-stage5c-dispatch-channels-platform.sql](sim-stage5c-dispatch-channels-platform.sql) | SIM stage5c dispatch channels platform 场景 SQL |
| [sim-stage5c-dispatch-file.sql](sim-stage5c-dispatch-file.sql) | SIM stage5c dispatch file 场景 SQL |
| [sim-stage6c-trigger-fixtures.sql](sim-stage6c-trigger-fixtures.sql) | SIM stage6c trigger fixtures 场景 SQL |
| [sim-stage6d-trigger-fixtures.sql](sim-stage6d-trigger-fixtures.sql) | SIM stage6d trigger fixtures 场景 SQL |
| [sim-stage6d-trigger-outbox-retry.sql](sim-stage6d-trigger-outbox-retry.sql) | SIM stage6d trigger outbox retry 场景 SQL |
| [sim-stage6d-trigger-pause.sql](sim-stage6d-trigger-pause.sql) | SIM stage6d trigger pause 场景 SQL |
| [sim-stage6d-trigger-resume.sql](sim-stage6d-trigger-resume.sql) | SIM stage6d trigger resume 场景 SQL |
| [sim06-sdk-dispatch-echo-fixtures.sql](sim06-sdk-dispatch-echo-fixtures.sql) | sim06 sdk dispatch echo fixtures 场景 SQL |
