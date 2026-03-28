# CI / Staging Gate

更新时间：2026-03-27

## 目标

本文件定义当前仓库的统一回归入口、推荐门禁层级和 staging 前的最低放行标准。

统一脚本入口：

- `scripts/ci/run-full-regression.sh`

## 脚本能力

当前脚本支持以下阶段：

1. reactor 默认测试：`*Test / *IntegrationTest`
2. reactor 显式 `*IT` 套件：补齐需要单独触发的集成测试和 E2E
3. workflow 级 `load-tests/` 额外 `test-compile`
4. 可选 load smoke：`JobLaunchSimulation`
5. 可选 deploy smoke：Helm `lint + template`
6. 可选巡检：`scripts/local/inspect-all.sh`

GitHub Actions Workflow：

- `.github/workflows/pr-gate.yml`
- `.github/workflows/full-ci-gate.yml`
- `.github/workflows/staging-gate.yml`
- `docs/testing/staging-live-deploy-smoke-checklist.md`

## 推荐门禁分层

### PR Gate

目标：快速发现受影响模块回归，不追求全量环境验证。

真实 workflow：

- `.github/workflows/pr-gate.yml`

建议命令：

```bash
bash scripts/ci/run-full-regression.sh --skip-it-suite -- --pl <changed-modules> -am -amd
```

要求：

- 受影响模块默认测试通过
- 不引入新的编译或启动错误
- 如 `load-tests/` 受影响，则额外执行 `mvn -q -f load-tests/pom.xml test-compile`

### Full CI Gate

目标：在合并前完成仓库级测试门禁。

真实 workflow：

- `.github/workflows/full-ci-gate.yml`

建议命令：

```bash
bash scripts/ci/run-full-regression.sh
```

要求：

- reactor 默认测试通过
- `*IT` 套件通过
- `load-tests/` 至少完成一次 `test-compile`

### Staging Gate

目标：在进入 staging 或发布前完成完整门禁。

真实 workflow：

- `.github/workflows/staging-gate.yml`

建议命令：

```bash
bash scripts/ci/run-full-regression.sh \
  --with-deploy-smoke \
  --with-deployment-verification \
  --with-load-smoke \
  --with-inspection
```

要求：

- Full CI Gate 全部通过
- Helm deploy smoke 通过
- 部署升级 / 回滚验证通过
- load smoke 通过
- 巡检脚本无 FAIL

## Deploy Smoke 说明

deploy smoke 现在分两层：

### A. 静态交付物校验

- `helm lint`：默认 values
- `helm lint`：prod overlay + dummy secret values
- `helm template`：默认 values 渲染
- `helm template`：prod overlay 渲染
- 对渲染结果做最小断言：
  - 六个 Deployment 存在
  - ConfigMap / Secret 存在
  - prod overlay 下 Ingress 存在
  - prod overlay 下 worker HPA 存在

脚本执行静态 deploy smoke 时：

- 优先使用本机 `helm`
- 如果本机没有 `helm`，回退到 Docker 里的 `alpine/helm:3.17.3`

### B. 真实 staging rollout / readiness 校验

当设置 `BATCH_DEPLOY_SMOKE_ENABLE_LIVE=true` 时，脚本会继续执行：

- `helm upgrade --install --wait`
- `kubectl rollout status` 校验六个 Deployment
- `kubectl port-forward` + `curl /actuator/health/readiness`

这一步依赖：

- 可用的 kube context
- 可用的 `kubectl`
- 集群中可访问的 PostgreSQL / Kafka / MinIO / 相关服务配置

关键环境变量：

- `BATCH_DEPLOY_SMOKE_ENABLE_LIVE`
- `BATCH_DEPLOY_SMOKE_RELEASE`
- `BATCH_DEPLOY_SMOKE_NAMESPACE`
- `BATCH_DEPLOY_SMOKE_VALUES_FILE`
- `BATCH_DEPLOY_SMOKE_TIMEOUT`
- `BATCH_DEPLOY_SMOKE_READINESS_TIMEOUT_SECONDS`
- `BATCH_DEPLOY_SMOKE_PLATFORM_DB_PASSWORD`
- `BATCH_DEPLOY_SMOKE_BUSINESS_DB_PASSWORD`
- `BATCH_DEPLOY_SMOKE_MINIO_ACCESS_KEY`
- `BATCH_DEPLOY_SMOKE_MINIO_SECRET_KEY`

实操清单见：

- `docs/testing/staging-live-deploy-smoke-checklist.md`

## Load Smoke 说明

当前 load smoke 只跑低强度写入压测：

- `JobLaunchSimulation`
- 默认 `users.peak=5`
- 默认 `duration.seconds=30`
- 默认 `ramp.seconds=10`

可通过环境变量覆盖：

- `BATCH_LOAD_SMOKE_USERS_PEAK`
- `BATCH_LOAD_SMOKE_DURATION_SECONDS`
- `BATCH_LOAD_SMOKE_RAMP_SECONDS`

## 建议放行标准

1. Full CI Gate 通过
2. deploy smoke 通过
3. staging load smoke 通过
4. 巡检无 FAIL
5. 无 Blocker / Critical 缺陷

## 当前未覆盖项

当前 deploy smoke 仍未覆盖：

- 真实业务链路级别的发布后验收
- `helm upgrade --install --atomic` 失败后的自动回滚观测

当前部署升级 / 回滚验证已经补齐了普通 rollback 路径，以上边界应继续作为下一轮加强项，重点放在 `--atomic` 失败观测和业务验收留档。

## Gate 收口状态

当前 `pr-gate.yml`、`full-ci-gate.yml`、`staging-gate.yml` 与 `scripts/ci/run-full-regression.sh` 已形成闭环：

- PR Gate 负责受影响范围的快速回归
- Full CI Gate 负责仓库级默认测试与 IT / E2E
- Staging Gate 负责 deploy smoke、部署升级 / 回滚验证、load smoke 和巡检

剩余的不是 gate 结构，而是 staging 留档和 `--atomic` 失败观测的补齐。
