# Staging Live Deploy Smoke Checklist

更新时间：2026-03-27

## 目标

本清单用于在真实 staging Kubernetes 集群上执行 live deploy smoke，并为发布评审保留一份可复用的操作顺序。

适用脚本：

- `scripts/ci/run-full-regression.sh --with-deploy-smoke`
- `scripts/ci/run-full-regression.sh --with-deployment-verification`

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
