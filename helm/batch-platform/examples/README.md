# Helm values 示例

本目录放**环境相关**的 override 文件，顶层 `values.yaml` 只保留生产默认值。

## 文件用途

| 文件 | 场景 |
|---|---|
| `values-local-k8s.yaml` | Mac Docker Desktop K8s + docker-compose 基础服务（Postgres/Kafka/MinIO/Redis）本地演示部署；连接走 `host.docker.internal:<HOST_PORT>` 到宿主机 compose 栈 |
| `values-minimal-replicas.yaml` | 单副本/双副本（orch）精简部署，总共 7 Pod，适合内存 8GB 的笔记本 demo |
| `values-startup-probes.yaml` | 所有模块挂 `startupProbe`（20s delay + 18×10s failureThreshold = 200s 启动窗口），避免 K8s 资源紧张下被 livenessProbe 误杀 |

## 组合使用

本地演示典型组合（Mac Docker Desktop K8s）：

```bash
# 前置：docker-compose 基础服务已启动（make dev-start 或
# docker compose --env-file .env.local up -d）

# 构建 7 个 app 镜像并打 batch-* 前缀（Dockerfile.app 会 COPY entrypoint.sh）
for m in batch-console-api batch-trigger batch-orchestrator \
         batch-worker-import batch-worker-export batch-worker-process \
         batch-worker-dispatch batch-worker-atomic; do
  DOCKER_BUILDKIT=1 docker build \
    --build-arg MODULE="$m" -f docker/Dockerfile.app \
    -t "batch-${m#batch-}:latest" --quiet .
  docker tag "${m#batch-}:latest" "batch-${m#batch-}:latest"
done

# 创建 namespace + helm install
kubectl create ns batch-prod
helm install batch helm/batch-platform/ \
  --namespace batch-prod \
  --values helm/batch-platform/examples/values-local-k8s.yaml \
  --values helm/batch-platform/examples/values-startup-probes.yaml \
  --values helm/batch-platform/examples/values-minimal-replicas.yaml

# 等 Ready
kubectl -n batch-prod wait --for=condition=Ready pods --all --timeout=5m
```

## 生产部署

不要用 `values-local-k8s.yaml` 做生产部署——里面 DB/MinIO 密码是明文、`host.docker.internal`
是 Mac 专属地址。生产应：

1. 复制默认 `values.yaml` 为 `values-prod.yaml`
2. 用 Secret / External Secrets Operator / Sealed Secrets 管理敏感字段
3. `DB_URL` / `REDIS_HOST` / `KAFKA_BOOTSTRAP_SERVERS` 指向真实生产集群
4. `security.bypassMode: "false"`（生产必须关；@PostConstruct 守护会在 prod profile 下拒绝 `true`)

参考 [../../docs/runbook/base-services-deployment.md](../../docs/runbook/base-services-deployment.md)。
