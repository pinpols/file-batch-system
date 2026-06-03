# Release Process — 2026-05-22 (ARCHIVED)

> **🛑 全文废弃(2026-06-03)**:本文档所描述的 `build-image.yml` 与 `promote-staging.yml` workflow 在当前仓库**均不存在**,所述链路无法执行。
>
> **当前发布流程以 [`releasing.md`](./releasing.md) 为唯一权威源**;本文件仅作为未来恢复 GitOps 时的历史蓝图保留,**不要按此执行**。
>
> 前置环境搭建见 [gitops-onboarding-2026-05-22.md](./gitops-onboarding-2026-05-22.md)。

---

## 全流程一图

```
[dev push main]
      │
      ▼
[build-image.yml]                          # GitHub Actions,本仓库
      │  build 7 个模块 → 推 ghcr.io,tag = sha-<long-sha> + latest
      ▼
[promote-staging.yml]                      # GitHub Actions,本仓库
      │  自动到 ops repo 提 PR,改 values-staging.yaml 7 个 image.tag
      ▼
[ops PR review + merge]                    # 人工 1 步
      │
      ▼
[Argo CD staging auto-sync]                # 集群,自动
      │  pull ops repo → helm upgrade
      ▼
[staging 验证(24h Grafana 稳)]            # 人工观察
      │
      ▼
[ops 手动改 values-prod.yaml → PR → merge] # 人工 promote 到 prod
      │
      ▼
[Argo CD prod sync(人工 approve)]         # Argo UI 点 Sync
      │
      ▼
[flagger Canary 渐进 10/20/30/40/50%]     # 自动,5 分钟跑完
      │
      ├─ metric OK ─→ promote 到 primary,流量 100% 切完
      └─ metric 失败 ─→ 自动 rollback,流量回 primary
```

---

## 1. 触发构建

dev 把 PR merge 到 `main`,自动触发:

- `build-image.yml`:7 个模块并行 build + push 到 `ghcr.io/pinpols/<module>:sha-<long-sha>` 和 `:latest`
- 多平台 amd64 + arm64,buildx cache 复用,正常 ~10-15 分钟

**人工检查**:GitHub Actions 页面看 7 个 job 全绿。任何一个红了就停,不要往下走。

---

## 2. 自动 promote 到 staging

`build-image.yml` 全绿后,`promote-staging.yml` 自动触发:

- checkout ops repo
- `yq` 改 `helm/values-staging.yaml` 的 7 个 `image.tag` 为 `sha-<long-sha>`
- 用 `peter-evans/create-pull-request` 提 PR
- PR 标题:`auto: promote <sha> to staging`,带 `auto-promote` label

**人工动作**:

- ops 团队 review PR(只是看 7 个 tag 是否都改对了),merge
- Argo CD 检测到 ops repo 变更,自动 sync staging namespace
- 等 Argo UI 显示 Healthy + Synced

---

## 3. staging 验证(24h 观察期)

merge 后,ops + dev 一起盯:

| 指标 | 阈值 | 看哪 |
|---|---|---|
| Pod ready | 7 个模块都 Ready | `kubectl get po -n batch-staging` 或 Argo UI |
| HTTP 成功率 | ≥ 99.5% | Grafana `request-success-rate` panel |
| p99 延迟 | ≤ 500ms | Grafana `request-duration` panel |
| Kafka consumer lag | 无堆积 | Grafana Kafka panel |
| 错误日志 | 无 ERROR 突增 | Loki / ELK |

**观察 24h** 稳定后,才能往 prod 推。中途出问题按 [§5 回滚](#5-回滚).

---

## 4. 手动 promote 到 prod

prod 不自动,**强制人工 promote**:

1. ops 在 ops repo 改 `helm/values-prod.yaml` 的 7 个 `image.tag` 为同一个 sha
   - 可参考 staging 用的 sha,或者用 release tag(如 `v1.2.3`)
2. 提 PR → review → merge
3. 打开 Argo CD UI → `batch-platform-prod` Application
4. **人工点 Sync 按钮**(prod Application 配的是 `syncPolicy: manual`,不会自动 sync)
5. flagger 接管,5 步渐进切流量(10% → 20% → 30% → 40% → 50% → promote 100%)
6. 每步 1min interval,共 ~5min;期间 flagger 自动监 metric

**观察点**:`kubectl describe canary -n batch-prod batch-platform-console-api`,看 `Status.Phase` 从 `Progressing` → `Succeeded`。

---

## 5. 回滚

### 5.1 Canary 期间自动 rollback

flagger 监到 metric 失败 5 次连续 → 自动:

- 流量切回 primary
- canary pod 缩 0
- `Status.Phase = Failed`
- Slack 告警(如果配了 webhook)

ops 不用做任何事,但**必须**事后看 Grafana / 日志查根因,fix 后重新发版。

### 5.2 Canary 通过但生产仍出问题

(metric 没抓到的业务异常,如脏数据 / 业务规则 bug)

**首选**:Argo UI → History and Rollback → 选上一个 healthy revision → Rollback。等价于 git revert ops repo 那次 promote commit。

### 5.3 紧急 hotfix 流程

当 Argo UI 异常 / sync 卡住 / 必须分钟级止血时:

```bash
# 1. 直接 kubectl context 切到 prod
kubectl config use-context prod

# 2. helm rollback 到上一个 revision(秒级生效)
helm history batch-platform -n batch-prod
helm rollback batch-platform <revision> -n batch-prod

# 3. 同步在 ops repo 提 revert PR,merge,避免 Argo 把你的手动改动 sync 回去
#    或者临时 disable auto-sync:argocd app set batch-platform-prod --sync-policy none
```

事后必须:

- 在 #incident slack 写明哪个 sha 出问题、回到了哪个 sha
- 在 ops repo 留对应 revert commit,确保 Argo desired state 跟集群实际一致
- 写 postmortem

---

## 6. 版本号 / Tag 规范

- main 每次 commit → image tag `sha-<long-sha>` + `latest`
- git tag `v1.2.3` push → 额外多个 image tag `v1.2.3`
- prod 推荐用 release tag(`v1.2.3`)做 `image.tag`,而不是 sha,便于人识别
- staging 用 sha,跟 commit 一一对应,自动化友好

---

## 7. 相关文档

- [gitops-onboarding-2026-05-22.md](./gitops-onboarding-2026-05-22.md)(环境搭建)
- [ci-cd-roadmap-2026-05-22.md](./ci-cd-roadmap-2026-05-22.md)(CI/CD 全景路线)
- [incident-response.md](./incident-response.md)(事故响应)
