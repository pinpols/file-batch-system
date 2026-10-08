# CI 门禁与发布验收

更新时间：2026-10-08

## 目标

本文件区分实际 GitHub Actions workflow、统一本地回归脚本和发布前人工验收。三者执行范围不同，不能将脚本支持的可选能力视为 CI 已执行的验证。

统一脚本入口：

- `scripts/ci/run-full-regression.sh`

## 脚本能力

当前脚本支持以下阶段：

1. reactor 默认测试：`*Test / *IntegrationTest`
2. reactor 显式 `*IT` 套件：补齐需要单独触发的集成测试和 E2E
3. workflow 级 `load-tests/` 额外 `test-compile`
4. 可选 load smoke：`JobLaunchSimulation`
5. 可选容量测试：`CapacityBaselineSimulation`
6. 可选部署验证：live rollout/readiness/rollback（需显式配置目标集群）
7. 可选巡检：`scripts/ops/inspect-all.sh`

GitHub Actions Workflow：

- `.github/workflows/pr-gate.yml`
- `.github/workflows/full-ci-gate.yml`
- `.github/workflows/staging-gate.yml`
- `docs/testing/release-gate.md`

## 推荐门禁分层

### PR Gate

目标：快速发现受影响模块回归，不追求全量环境验证。

真实 workflow：

- `.github/workflows/pr-gate.yml`

建议入口：

```bash
python3 scripts/ci/detect-change-scope.py --base origin/main --head HEAD --json
bash scripts/ci/run-full-regression.sh --skip-it-suite -- --pl <affected-modules> -am
```

`<affected-modules>` 应对齐 `pr-gate.yml` 的固定分片模块列表，不使用无边界的 `-amd` 扩散。
需要完全复现线上路由时，以 `.github/actions/detect-change-scope` 的四个单元分片输出为准。

要求：

- 受影响模块默认测试通过
- 不引入新的编译或启动错误
- 如 `load-tests/` 受影响，则额外执行 `mvn -q -f load-tests/pom.xml test-compile`

### Full CI Gate

目标：合并到 main 后执行主干全量回归；它不是 PR 合并前的 required check。

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

### Nightly E2E Gate

目标：在 GitHub-hosted runner 上定时或手动运行六片全量 E2E 和 Java 治理检查。当前 workflow 不部署到 staging 集群，也不执行 live rollout、负载测试或巡检。

真实 workflow：

- `.github/workflows/staging-gate.yml`

需要在本地或已配置的受控环境执行发布前扩展验证时，可运行：

```bash
bash scripts/ci/run-full-regression.sh \
  --with-deploy-smoke \
  --with-deployment-verification \
  --with-load-smoke \
  --with-inspection
```

以上命令只是脚本可选能力，不代表 nightly E2E workflow 会执行这些步骤。实际发布前应根据变更风险明确运行并留存对应证据：

- Full CI Gate 全部通过
- Helm deploy smoke 通过
- 部署升级 / 回滚验证通过
- load smoke 通过
- 巡检脚本无 FAIL

### Daily SIM / strict validation

`.github/workflows/daily-sim-strict-validation.yml` 是独立的定时真实数据验证，不属于 `staging-gate`。它按计划日期是否有代码/配置变更决定是否运行；手动触发使用当前北京时间日期。SIM 与 strict 分别记录结果，只有两者成功才会进入 nightly 镜像构建。workflow 被跳过、取消、runner 丢失或环境未成功启动均不能算验证通过。

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

- `docs/testing/release-gate.md`

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

## 当前 workflow 边界

当前 GitHub Actions 的实际职责：

- PR Gate 负责合入前、按变更范围路由的检查和测试。
- Full CI Gate 在 main push 后运行全量回归，并按周或手动执行质量分析。
- `staging-gate` 每日定时或手动执行全量 E2E 和 Java 治理，不连接 staging 集群。
- Daily SIM/strict workflow 独立运行真实数据验证；是否执行取决于计划日期的代码/配置变更检测或手动触发。

本地脚本的 deploy smoke、live deployment verification、load smoke/capacity 和 inspection 是可选能力。它们只有在命令实际执行并保存结果后，才能作为对应环境的验证证据；不能由 nightly E2E 的成功状态推导出来。

---

# Staging Live Deploy Smoke Checklist（合并自 release-gate.md）


更新时间：2026-03-27

## 目标

本清单用于在真实 staging Kubernetes 集群上执行 live deploy smoke，并为发布评审保留一份可复用的操作顺序。

适用脚本：

- `scripts/ci/run-full-regression.sh --with-deploy-smoke`
- `scripts/ci/run-full-regression.sh --with-deployment-verification`
- `scripts/ci/run-staging-live-smoke.sh`

说明：

- 本地 `docker-desktop`、`kind` 或其他开发集群，只能验证脚本逻辑，不能替代 staging 放行证据

## 前置条件

执行前先确认：

1. `kubectl config current-context` 指向真实 staging 集群
2. 镜像已推送到 staging 可访问的镜像仓库
3. staging 集群已具备或可访问 PostgreSQL、Kafka、MinIO 及相关网络策略
4. `helm`、`kubectl`、`curl` 可用；若本机无 `helm`，脚本可自动回退到 Docker 里的 `alpine/helm:3.17.3`
5. values 文件已准备好；若没有专门的 staging overlay，则显式指定当前准备用于 staging 的 values 文件
6. 数据库、MinIO 等敏感凭据已通过环境变量注入

## 建议环境变量

最小建议值：

```bash
export BATCH_DEPLOY_SMOKE_ENABLE_LIVE=true
export BATCH_DEPLOY_SMOKE_RELEASE=batch-platform-staging-smoke
export BATCH_DEPLOY_SMOKE_NAMESPACE=batch-staging-smoke
export BATCH_DEPLOY_SMOKE_VALUES_FILE=helm/values-prod.yaml
export BATCH_DEPLOY_SMOKE_TIMEOUT=10m
export BATCH_DEPLOY_SMOKE_READINESS_TIMEOUT_SECONDS=180

export BATCH_DEPLOY_SMOKE_PLATFORM_DB_PASSWORD='***'
export BATCH_DEPLOY_SMOKE_BUSINESS_DB_PASSWORD='***'
export BATCH_DEPLOY_SMOKE_MINIO_ACCESS_KEY='***'
export BATCH_DEPLOY_SMOKE_MINIO_SECRET_KEY='***'
```

如果后续新增 `helm/values-staging.yaml`，优先改成：

```bash
export BATCH_DEPLOY_SMOKE_VALUES_FILE=helm/values-staging.yaml
```

## 执行顺序

### 1. 先做上下文确认

```bash
kubectl config current-context
kubectl get ns
```

要求：

- context 明确是 staging
- 当前账号对目标 namespace 有安装、查询、端口转发权限

### 2. 先跑 deploy smoke 专项

只验证部署产物和 live rollout / readiness：

```bash
LOG_FILE="/tmp/staging-live-deploy-smoke-$(date +%Y%m%d-%H%M%S).log"

BATCH_DEPLOY_SMOKE_ENABLE_LIVE=true \
bash scripts/ci/run-full-regression.sh \
  --skip-default-tests \
  --skip-it-suite \
  --with-deploy-smoke | tee "$LOG_FILE"
```

或者直接使用组合入口：

```bash
LOG_FILE="/tmp/staging-live-smoke-$(date +%Y%m%d-%H%M%S).log"

bash scripts/ci/run-staging-live-smoke.sh | tee "$LOG_FILE"
```

脚本会自动执行：

1. `helm lint`
2. `helm template`
3. manifest 关键对象断言
4. `helm upgrade --install --wait`
5. `kubectl rollout status`
6. `port-forward + /actuator/health/readiness`

### 3. 如需完整 staging gate，再跑全量命令

```bash
LOG_FILE="/tmp/staging-full-gate-$(date +%Y%m%d-%H%M%S).log"

BATCH_DEPLOY_SMOKE_ENABLE_LIVE=true \
bash scripts/ci/run-full-regression.sh \
  --with-deploy-smoke \
  --with-load-smoke \
  --with-inspection | tee "$LOG_FILE"
```

### 4. 如需做升级 / 回滚验证，再跑部署验证专项

```bash
LOG_FILE="/tmp/staging-deployment-verification-$(date +%Y%m%d-%H%M%S).log"

BATCH_DEPLOY_VERIFICATION_ENABLE_LIVE=true \
bash scripts/ci/run-full-regression.sh \
  --with-deployment-verification | tee "$LOG_FILE"
```

## 结果核对项

执行完成后，至少补查以下内容：

1. `kubectl -n "$BATCH_DEPLOY_SMOKE_NAMESPACE" get pods`
2. `kubectl -n "$BATCH_DEPLOY_SMOKE_NAMESPACE" get deploy`
3. `kubectl -n "$BATCH_DEPLOY_SMOKE_NAMESPACE" get svc`
4. `kubectl -n "$BATCH_DEPLOY_SMOKE_NAMESPACE" get ingress`
5. `kubectl -n "$BATCH_DEPLOY_SMOKE_NAMESPACE" get hpa`
6. `kubectl -n "$BATCH_DEPLOY_SMOKE_NAMESPACE" describe deploy <release>-trigger`
7. `kubectl -n "$BATCH_DEPLOY_SMOKE_NAMESPACE" logs deploy/<release>-orchestrator --tail=200`

放行要求：

- 六个 Deployment 全部 rollout 成功
- 六个 `/actuator/health/readiness` 全部返回 `UP`
- 无 CrashLoopBackOff / ImagePullBackOff
- `inspect-all.sh` 无 `FAIL`
- 如启用 load smoke，则压测期间无明显错误率抬升

## 留档内容

建议至少保留以下证据：

1. 终端日志文件
2. `kubectl get pods,deploy,svc,ingress,hpa -o wide`
3. 失败时的 `kubectl describe` 和关键容器日志
4. 本次使用的 values 文件版本和镜像 tag
5. 执行时间、执行人、context 名称、namespace、release 名称

## 清理

如果使用的是一次性 smoke namespace，结束后清理：

```bash
helm uninstall "$BATCH_DEPLOY_SMOKE_RELEASE" -n "$BATCH_DEPLOY_SMOKE_NAMESPACE"
kubectl delete namespace "$BATCH_DEPLOY_SMOKE_NAMESPACE"
```

如果 staging namespace 是长期环境，则不要直接删除，按环境运维规范清理或保留。

## 当前边界

本清单覆盖：

- live install / upgrade
- rollout
- readiness

本清单暂未覆盖：

- 回滚后业务链路级验收
- 数据迁移前后兼容窗口验证
