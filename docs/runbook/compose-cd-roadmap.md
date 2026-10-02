# 前后端持续部署路线：Compose CD → Kubernetes GitOps

> 状态：规划 / 待实施
> 适用仓库：file-batch-system + batch-console
> 决策：当前优先 Docker Compose CD；Helm / Kubernetes / Argo CD 保留为后续高可用路线。

## 1. 决策

当前已有 GitHub Actions、GHCR、后端 deploy/docker/compose/app.deploy.yml、前端 docker-compose.deploy.yml、健康检查、前端真实 staging E2E/Lighthouse，以及后端 Helm/HA 资产。

现阶段目标环境仍以裸 Linux / 单机或少量节点为主。直接引入 Kubernetes + Argo CD 会同时增加集群、Ingress、Secret、GitOps 仓库、Argo Application 和有状态组件运维成本。因此采用两阶段路线。

## 2. Phase 1：Compose CD

目标链路：

    GitHub Actions
      -> 构建前后端镜像
      -> 推送 GHCR，记录 immutable digest
      -> staging 自动 SSH + Compose 部署
      -> 健康检查 + Git SHA/digest 校验 + E2E
      -> 失败恢复上一稳定 release set
      -> production Environment 人工审批
      -> SSH + Compose 晋级同一组 digest
      -> 失败回滚上一组 digest

核心原则：

1. Build once, promote many：staging 与 production 使用同一镜像 digest，production 不重新构建。
2. digest 是部署事实：sha-<commit> 可保留为可读标签，但禁止依赖 latest。
3. 前后端作为一个 release set 晋级，不分别选择“最新镜像”。
4. staging 自动，production 使用 GitHub Environment approval。
5. 部署机只 pull/up，不 clone 源码，不 Maven/npm build。
6. 发布前保存上一稳定 release set；健康、版本或关键 smoke 失败时恢复。
7. SSH、registry、测试账号等凭据只进入 GitHub Environment/Secrets 或服务器本地 secret。

## 3. 统一 release manifest

一次平台发布至少记录：

    releaseId: <id>
    frontend: ghcr.io/pinpols/batch-console@sha256:...
    console-api: ghcr.io/pinpols/console-api@sha256:...
    trigger: ghcr.io/pinpols/trigger@sha256:...
    orchestrator: ghcr.io/pinpols/orchestrator@sha256:...
    worker-import: ghcr.io/pinpols/worker-import@sha256:...
    worker-export: ghcr.io/pinpols/worker-export@sha256:...
    worker-process: ghcr.io/pinpols/worker-process@sha256:...
    worker-dispatch: ghcr.io/pinpols/worker-dispatch@sha256:...
    worker-atomic: ghcr.io/pinpols/worker-atomic@sha256:...

还应记录前后端 Git SHA、staging 验收结果、production 审批/部署结果和上一稳定 release set。

## 4. Compose 部署边界

后端复用 deploy/docker/compose/app.deploy.yml，前端复用 batch-console/docker-compose.deploy.yml。

当前 Compose 主要以 IMAGE_TAG 选择镜像。实施 CD 时需要支持由 release manifest 注入不可变 digest。统一 Git SHA 可以继续作为展示标签，但运行容器必须能反查 RepoDigest 并与 manifest 一致。

数据库迁移是特殊边界：Flyway 不可逆迁移时，镜像回滚不等于数据库回滚。涉及 schema 的 release 必须说明 backward compatibility、备份和恢复方案。

## 5. staging 验收

staging 自动部署后至少执行：

- Compose 服务 healthy/ready；
- Console API、Trigger、Orchestrator 关键健康检查；
- 前端 /healthz；
- 前端 /version.json Git SHA 校验；
- 容器 RepoDigests 与 release manifest 校验；
- 后端 smoke / staging live smoke；
- 前端现有真实 Playwright、视觉回归和 Lighthouse；
- 任一关键 gate 失败：该 release set 不可晋级，并恢复上一稳定 release set。

## 6. production 与回滚

production workflow 绑定 GitHub production Environment，并要求人工审批。

步骤：读取 staging 已通过 manifest → 保存当前 production manifest → SSH → pull digest → Compose up -d --no-build → health/version/digest verify → 成功后登记 stable；失败则恢复上一 manifest 并再次健康检查。

## 7. Phase 2：Kubernetes + Helm + GitOps

进入条件：

- 需要多节点/跨故障域；
- Compose 恢复时间无法满足 SLA；
- 需要标准滚动升级、自动重调度、HPA/KEDA；
- PostgreSQL/Kafka/Redis/MinIO 正式进入 HA；
- 已具备稳定 Kubernetes/SRE 运维能力。

目标链路：

    GitHub Actions
      -> push immutable images
      -> 更新独立 deployment/ops repo 的 digest
      -> Argo CD sync staging
      -> staging 验收
      -> production promotion PR / approval
      -> Argo CD sync production

Phase 2 继续复用 Phase 1 的 build-once、digest 晋级、release set、staging 验收和 production 审批语义。现有 helm/batch-platform 与 deploy/ha 继续维护和静态验证，但 Phase 1 不把 Argo CD 当生产 CD 前置条件。

## 8. 待办

### P0：Compose CD 最小闭环

- [ ] 后端 build-image 输出每个模块 immutable digest。
- [ ] 前端 build-image 输出 frontend immutable digest。
- [ ] 定义统一 release manifest schema、存储和审计方式。
- [ ] 建立 staging / production GitHub Environments；production 开人工审批。
- [ ] 配置 staging/prod SSH host、user、key、known_hosts，禁止关闭 host key 校验。
- [ ] Linux deploy 脚本：precheck → pull → up → health → version/digest verify。
- [ ] staging 在制品完成后自动部署。
- [ ] staging 串联后端 smoke 与前端真实 E2E/Lighthouse。
- [ ] 保存上一 stable manifest，实现失败自动回滚。
- [ ] production 只允许晋级 staging 已通过的同一 release set。
- [ ] production 发布后再次 health/version/digest verify。
- [ ] Actions summary 记录目标/上一 digest、环境和结果。

### P1：可靠性与安全

- [ ] SSH 最小权限部署用户。
- [ ] GHCR 生产凭据只需 pull。
- [ ] 同一环境 deployment concurrency lock。
- [ ] 部署超时、失败日志与容器状态快照。
- [ ] Flyway 向前兼容/不可逆迁移回滚规则。
- [ ] 最近 N 个已验证 release set retention。
- [ ] 演练前端失败、单后端模块失败、健康失败、SSH 中断、pull 失败。
- [ ] break-glass 手工发布/回滚 runbook。

### P2：GitOps / HA

- [ ] 保持 Helm lint、生产 values、安全和配置漂移门禁。
- [ ] 完成 deploy/ha 对应 PG/Kafka/Redis/MinIO 的真实故障演练。
- [ ] 明确 K8s 节点、故障域、StorageClass、Ingress、Secret 管理。
- [ ] 建立独立 deployment/ops repo 后再引入 Argo CD。
- [ ] staging 先 GitOps 化，稳定后迁 production。
- [ ] 过渡期保留 Compose CD 作为受控回退路径。

## 9. 当前不做

- 不为了 CD 单独引入 Kubernetes。
- 不在 production 重新构建。
- 不用 latest 晋级。
- 不让 staging/prod 各自取“最新”前后端镜像。
- 不通过 SSH 在服务器源码构建。
- 不把已有 Argo/Helm 骨架描述成已落地 GitOps。

## 10. Phase 1 完成标准

1. 前后端镜像均有不可变 digest。
2. staging 可无人值守部署指定 release set。
3. staging 能验证健康、版本/digest 和真实 E2E。
4. production 必须人工批准，只晋级 staging 已通过的同一 release set。
5. 任一部署失败可恢复上一稳定 release set，并产生审计结果。
6. 部署机无需源码构建工具链。
7. GitHub run 可追溯 commit、image digest、环境和结果。
