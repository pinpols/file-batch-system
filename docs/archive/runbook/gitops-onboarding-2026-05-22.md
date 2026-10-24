# GitOps Onboarding(Argo CD + flagger)— 2026-05-22

> **2026-10-09 状态**：GitOps 尚未连接真实 ops 仓和集群。仓库已提供 [ops 仓模板](../../deploy/gitops/ops-repo-template/README.md)、按 release manifest 生成逐镜像 digest values 的脚本，以及 Helm 外部 Secret 引用；promotion PR、Argo CD sync 和 staging smoke 仍需外部接入验收。
>
> 给 ops 团队的入门 runbook。**不假设读者懂 Argo CD / flagger**,从 0 装到能跑。
> 本仓库提供 `build-image.yml`、Helm Chart、ops 仓模板和 digest values 生成工具，**但尚未连接集群**。
> 这份文档说明外部接入步骤和未完成的验收边界。

---

## 1. 前置依赖清单

接入前,确认下列东西到位:

| 依赖 | 用途 | 状态 |
|---|---|---|
| Kubernetes 集群(staging + prod 至少各一套) | 部署目标 | TODO ops 提供 kubeconfig |
| Argo CD(v2.10+) | GitOps 控制器,监听 ops repo 自动 sync | TODO 安装 |
| flagger(v1.36+) | Canary 渐进式发布控制器 | TODO 安装(仅 prod 必须,staging 可选) |
| Prometheus + ServiceMonitor CRD | flagger 拉 metric 用 | TODO 安装 |
| Service mesh / Ingress(nginx/istio 二选一) | flagger 切流量用 | TODO 选型 |
| ghcr.io PAT(`GHCR_TOKEN`) | 集群拉镜像 + CI 推镜像 | TODO 在 GitHub Settings → Secrets 配 |
| ops repo 写权限 | promotion 自动化向 ops repo 提 PR | 当前使用人工审查/提交；接入 CI 自动提 PR 时再配置 GitHub App 或短期凭据 |
| Slack webhook(可选) | 发布通知 / canary 失败告警 | TODO |

---

## 2. 第二个 repo(ops)目录结构

按 GitOps 最佳实践,**应用代码**和**部署声明**分两个 repo:

- 应用代码:**本仓库** `pinpols/file-batch-system`(Java + Helm chart)
- 部署声明:**新建** `pinpols/file-batch-system-ops`(values + Argo Application)

ops repo 目录示例（可从 `deploy/gitops/ops-repo-template` 初始化）：

```
file-batch-system-ops/
├── README.md
├── argo/
│   └── application-staging.yaml      # Argo Application 指 staging
└── environments/
    └── staging/
        ├── values.yaml               # 环境配置，不含凭据
        └── release-images.yaml        # release manifest 生成的逐镜像 digest
```

### 2.1 `argo/application-staging.yaml` 模板

完整模板位于 [`deploy/gitops/ops-repo-template/argo/application-staging.yaml`](../../deploy/gitops/ops-repo-template/argo/application-staging.yaml)。其关键配置如下：

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: batch-platform-staging
  namespace: argocd
spec:
  project: default
  # multi-source:本 repo 提供 chart,ops repo 提供 values
  sources:
    - repoURL: https://github.com/pinpols/file-batch-system.git
      targetRevision: main
      path: helm/batch-platform
      helm:
        valueFiles:
          - $values/environments/staging/values.yaml
          - $values/environments/staging/release-images.yaml
    - repoURL: https://github.com/pinpols/file-batch-system-ops.git
      targetRevision: main
      ref: values
  destination:
    server: https://kubernetes.default.svc
    namespace: batch-staging
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
```

### 2.2 Production Application

与 staging 几乎一致,差异:

- `targetRevision` 可指向 tag(如 `v1.2.3`)锁定 prod 版本
- `valueFiles` 加 `values-canary.yaml` 启用 flagger
- `syncPolicy.automated` **去掉**,prod 强制人工 approve in Argo UI

---

## 3. Secret 管理

Helm 可通过 `security.existingSecretName` 引用由外部密钥控制器预先创建在目标 namespace 中的共享 Secret，此时 Chart 不渲染自己的 Secret，也不会把运行时凭据写进 Helm values/release state。生产 overlay 默认引用 `batch-platform-runtime`。Secret 至少应包含以下键（依启用功能提供可选键）：

`BATCH_PLATFORM_DB_PASSWORD`、`BATCH_BUSINESS_DB_PASSWORD`、`BATCH_CONSOLE_PRIMARY_PASSWORD`、`BATCH_CONSOLE_REPLICA_PASSWORD`、`BATCH_S3_ACCESS_KEY`、`BATCH_S3_SECRET_KEY`、`BATCH_STORAGE_FILESYSTEM_PRESIGN_SECRET`、`BATCH_INTERNAL_SECRET`、`BATCH_CONSOLE_JWT_SECRET`、`BATCH_CONSOLE_AI_OPENAI_COMPATIBLE_API_KEY`、`BATCH_SECURITY_BYPASS_MODE`、`BATCH_CONSOLE_SECURITY_LOGIN_ENCRYPTION_PRIVATE_KEY_PEM`、`BATCH_CONSOLE_SECURITY_LOGIN_ENCRYPTION_PUBLIC_KEY_PEM`。

外部 Secret 更新不会被 Helm 读取明文，也不会自动触发 Pod 重启。Secret 控制器需提供 rollout reloader，或在 ops values 更新非敏感的 `security.existingSecretRevision` 以触发 Deployment 滚动。Chart 渲染阶段无法读取外部 Secret 内容；生产 profile 的应用启动校验仍负责拒绝缺失或强度不足的关键密钥。OIDC Client Secret 仍使用 `consoleApi.sso.oidc.clientSecretSecretName` 单独引用。

| Secret | 配置位置 | 内容 |
|---|---|---|
| `OPS_REPO_TOKEN` | GitHub Repo Settings → Secrets → Actions | PAT,scope: `repo` 写权限到 `pinpols/file-batch-system-ops` |
| `GHCR_TOKEN` | 集群 imagePullSecret + 本 repo Secrets(可选) | PAT,scope: `read:packages`(集群拉)/ `write:packages`(本 repo 推镜像;workflow 内建 `GITHUB_TOKEN` 已够,通常不用单配) |
| Slack webhook | flagger / Argo CD notification config | 标准 incoming webhook URL |
| 数据库 / Kafka / Redis 密码 | 集群 Sealed Secret 或 External Secrets Operator | 不在 ops repo 明文存 |

集群侧创建 ghcr pull secret 示例(每个 namespace 都要建):

```bash
kubectl create secret docker-registry ghcr-pull \
  --docker-server=ghcr.io \
  --docker-username=<github-user> \
  --docker-password=<GHCR_TOKEN> \
  --namespace=batch-staging
```

在 helm values 里引用:

```yaml
imagePullSecrets:
  - name: ghcr-pull
```

---

## 4. TODO 列表(ops 团队接入步骤)

按顺序做:

- [ ] **创建 ops repo** `pinpols/file-batch-system-ops`,按 §2 目录结构 init
- [ ] **GitHub Secrets** 配 `OPS_REPO_TOKEN`(本仓库)
- [ ] **集群安装 Argo CD**(`kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml`)
- [ ] **集群安装 flagger**(`helm install flagger flagger/flagger -n flagger-system`,prod 必装)
- [ ] **集群安装 Prometheus**(kube-prometheus-stack 一次性执行)
- [ ] **配 nginx ingress 或 istio**(flagger 切流量依赖)
- [ ] **每个 namespace 建 ghcr-pull secret**
- [ ] **`kubectl apply` 两个 Argo Application**(staging + prod)
- [ ] **配 Slack webhook**(flagger 告警 + Argo CD notification)
- [ ] **跑一遍端到端**:本 repo push main → build-image → promote PR → ops merge → Argo sync → staging 起来

---

## 5. 回滚

### 5.1 Argo UI 一键 rollback(首选)

1. Argo CD UI → `batch-platform-staging`(或 prod) → History and Rollback
2. 选上一个 healthy revision → Rollback
3. Argo 会自动 sync 到该 revision 的 helm values(等价于 ops repo 那次 commit)

### 5.2 紧急情况手动 helm rollback(回退)

Argo UI 异常退出 / 仍在 sync 卡住时:

```bash
kubectl config use-context prod
helm history batch-platform -n batch-prod
helm rollback batch-platform <revision> -n batch-prod
```

**注意**:手动 rollback 后 Argo 会检测到 drift,需要在 Argo UI 手动 `disable auto-sync` 或同步修改 ops repo,否则 Argo 会把你回滚的内容再 sync 回去。

### 5.3 flagger 自动 rollback

Canary 期间 metric 连续 5 次失败(成功率 < 99% 或 p99 > 500ms),flagger 自动回退流量到 primary,无需人工干预。事后查 `kubectl describe canary -n batch-prod batch-platform-console-api`。

---

## 6. 参考

- Argo CD docs: https://argo-cd.readthedocs.io/
- flagger docs: https://docs.flagger.app/
- 本仓库 `helm/batch-platform/values-canary.yaml`(canary 模式 values)
- 本仓库 `helm/batch-platform/templates/canary-console.yaml`(Canary CRD 模板)
- 发布操作流程: [releasing.md](./releasing.md)(权威);历史蓝图见 [release-process-2026-05-22.md](../archive/runbook/release-process-2026-05-22.md)
