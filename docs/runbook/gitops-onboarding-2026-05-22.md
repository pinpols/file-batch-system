# GitOps Onboarding(Argo CD + flagger)— 2026-05-22

> **⚠️ 2026-05-23 状态更新**:`promote-staging.yml` 已删除(从未接通,见 [ci.md](./ci.md) 顶部说明)。本文档描述的是**未来若要恢复 ops 仓自动同步**的完整链路;当前 main commit → ops 仓同步走人工 SOP。
>
> 给 ops 团队的入门 runbook。**不假设读者懂 Argo CD / flagger**,从 0 装到能跑。
> 本仓库已经写好 `build-image.yml` / `helm/` 骨架,**但都没接集群**。
> 这份文档是把"接集群"那部分变成 checklist。

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
| ops repo PAT(`OPS_REPO_TOKEN`) | promote-staging.yml 提 PR 到 ops repo | TODO 在 GitHub Settings → Secrets 配 |
| Slack webhook(可选) | 发布通知 / canary 失败告警 | TODO |

---

## 2. 第二个 repo(ops)目录结构

按 GitOps 最佳实践,**应用代码**和**部署声明**分两个 repo:

- 应用代码:**本仓库** `pinpols/file-batch-system`(Java + Helm chart)
- 部署声明:**新建** `pinpols/file-batch-system-ops`(values + Argo Application)

ops repo 目录示例(**本仓库不创建,留给 ops 团队 init**):

```
file-batch-system-ops/
├── README.md
├── argo/
│   ├── staging-application.yaml      # Argo Application 指 staging
│   └── prod-application.yaml         # Argo Application 指 prod
└── helm/
    ├── values-staging.yaml           # 7 个模块的 image.tag(promote-staging.yml 自动更新)
    └── values-prod.yaml              # prod values(手动 promote)
```

### 2.1 `argo/staging-application.yaml` 模板

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
          - values.yaml
          - $values/helm/values-staging.yaml
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

### 2.2 `argo/prod-application.yaml` 模板

与 staging 几乎一致,差异:

- `targetRevision` 可指向 tag(如 `v1.2.3`)锁定 prod 版本
- `valueFiles` 加 `values-canary.yaml` 启用 flagger
- `syncPolicy.automated` **去掉**,prod 强制人工 approve in Argo UI

---

## 3. Secret 管理

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
- [ ] **集群安装 Prometheus**(kube-prometheus-stack 一把梭)
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

### 5.2 紧急情况手动 helm rollback(兜底)

Argo UI 挂了 / 仍在 sync 卡住时:

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
- 发布操作流程: [release-process-2026-05-22.md](./release-process-2026-05-22.md)
