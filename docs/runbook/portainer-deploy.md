# Portainer CE 部署 runbook(BE + FE)

> 取代 GitHub Actions self-hosted runner 的 deploy workflow。
> Portainer 直接在部署机本地拉 Git → `docker compose build` → `up -d`,
> 不再依赖 18081 代理 / GHCR pull / workflow yml 调试,故障收敛在 docker + git。

适用:Windows / Linux 单机 Docker Desktop or Docker Engine。两个 stack:
- **batch-platform**(BE 9 模块 + pg-primary/replica + redis + minio + kafka)
- **batch-platform-console**(FE nginx + dist)

---

## 一、装 Portainer CE(部署机一次,约 5 min)

Windows(Docker Desktop)PowerShell:
```powershell
docker volume create portainer_data
docker run -d `
  --name portainer `
  --restart=always `
  -p 9000:9000 -p 9443:9443 `
  -v //./pipe/docker_engine:/var/run/docker.sock `
  -v portainer_data:/data `
  portainer/portainer-ce:latest
```

Linux(Docker Engine)bash:
```bash
docker volume create portainer_data
docker run -d \
  --name portainer \
  --restart=always \
  -p 9000:9000 -p 9443:9443 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v portainer_data:/data \
  portainer/portainer-ce:latest
```

浏览器开 https://localhost:9443 → 首次设管理员密码 → 选 "Get Started"(local environment 自动识别)。

---

## 二、配 BE Stack(`batch-platform`)

进 **Stacks → Add stack**。

| 字段 | 值 |
|---|---|
| Name | `batch-platform`(必须,容器/卷/网络命名空间) |
| Build method | **Repository** |
| Repository URL | `https://github.com/pinpols/file-batch-system` |
| Repository reference | `refs/heads/feature/docker-deploy` |
| Authentication | (公仓不填;私仓配 GitHub PAT) |
| Compose path | `docker-compose.yml,docker/compose/app.yml,docker/compose/app.deploy.yml` |
| Skip TLS verification | 否 |
| **Enable GitOps updates** | ✅ |
| Mechanism | `Polling` |
| Polling interval | `1m`(或更长,看你接受多大延迟) |
| Force redeploy | 仅 GitOps update 时(可选) |

**Environment variables**(对照部署机 `.env.local`,把以下从 .env.local 拷进 Portainer UI 的 env 区):
```
COMPOSE_PROJECT_NAME=batch-platform
BATCH_PLATFORM_DB_PASSWORD=batch_pass_123          # 与 .env.local 同
BATCH_BUSINESS_DB_PASSWORD=batch_pass_123
BATCH_MINIO_ACCESS_KEY=minioadmin
BATCH_MINIO_SECRET_KEY=minioadmin123
BATCH_INTERNAL_SECRET=local-internal-secret-32-chars
SPRING_PROFILES_ACTIVE=local
BATCH_SECURITY_BYPASS_MODE=true
# 端口
POSTGRES_PORT=15432
POSTGRES_REPLICA_PORT=15433
KAFKA_HOST_PORT=19092
MINIO_API_PORT=19000
MINIO_CONSOLE_PORT=19001
REDIS_PORT=16379
CONSOLE_API_PORT=18090
TRIGGER_PORT=18091
ORCHESTRATOR_PORT=18082
```

> 完整变量见仓内 `.env.example`(Portainer UI 支持从 .env 文件批量导入)。

**Stack-level profiles**:Portainer 不直接支持 compose profile 选择,需在 compose 顶层把
`apps`/`replica` 之外的 service 也走 default profile,或用 `--profile` 走 webhook 触发模式。
最简单:把 compose 拷贝时去掉 `profiles: [apps]` 限制(已部署的 stack 总是要起 apps + replica),
或专门给 Portainer 维护一份不带 profile 的 `docker/compose/app.portainer.yml`。

→ Submit。Portainer 自动 clone repo + build + up,过程在 Stack 详情页有日志。

---

## 三、配 FE Stack(`batch-platform-console`)

| 字段 | 值 |
|---|---|
| Name | `batch-platform-console` |
| Build method | Repository |
| Repository URL | `https://github.com/pinpols/batch-console` |
| Repository reference | `refs/heads/main`(FE deploy 文件在 main)|
| Compose path | `docker-compose.yml` |
| GitOps updates | ✅ Polling 1m |

**Environment variables**:
```
COMPOSE_PROJECT_NAME=batch-platform-console
NGINX_HOST_PORT=19080      # 与 batch-platform 那边的 console-api 18090 配合
BE_INTERNAL=http://host.docker.internal:18090
```

(以 FE `.env.example` 为准)

---

## 四、验证 + 排障

**验证**:
```bash
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.CreatedAt}}"
# 容器 Up 几秒 + healthy = 部署成功
curl http://localhost:18090/actuator/health  # BE
curl http://localhost:19080/                  # FE
```

**Portainer 上看实时**:Stack 详情 → Logs(集合 stdout/stderr)、Containers → 各容器 inspect / exec / restart。

**回滚**:Stack → Editor → 改 Repository reference 到上一个绿的 sha(或 main^),Submit redeploy。或直接在 GitHub 上 `git revert`,Portainer 1 min 内自动拉新。

**强制重新拉构建**:Stack → "Pull and redeploy"(忽略 polling 等待)。

---

## 五、迁移后清理(确认 Portainer 稳定后)

1. **停部署 runner 计划任务**(部署机上):
   ```powershell
   Get-ScheduledTask GHRunner-* | Disable-ScheduledTask
   # 或彻底停 runner 服务/进程
   ```
2. **(可选)从 GitHub 注销 runner**:仓库 Settings → Actions → Runners → Remove
3. **保留** `pr-gate.yml` / `codeql.yml` / `full-ci-gate.yml` 等 CI 工作流(PR 门禁/安全扫,Portainer 替代不了)

---

## 六、私有仓 / 鉴权场景(目前公仓不需要)

若仓库转私有:Portainer → Settings → Authentication → 添加 GitHub PAT(scope: `repo`),
Stack 编辑时勾选 Authentication 引用该 credential。

## 七、Portainer 自身备份

`portainer_data` volume 含全部配置(stack 定义 / 用户 / settings)。定期:
```bash
docker run --rm -v portainer_data:/source -v $PWD:/backup alpine \
  tar czf /backup/portainer-$(date +%F).tar.gz -C /source .
```

恢复:reverse 操作,然后 `docker run portainer-ce` 起来即可。
