# 本地脚本 + Windows 计划任务部署

> 2026-05-28 起的部署机制(取代 GH Actions CD 与 Portainer 尝试)。
> 单机 Docker Compose 场景的成熟答案:零依赖、零代理、零外部服务。

## 设计

- 部署机两个 PowerShell 脚本(`C:\Users\aa\scripts\deploy-be.ps1` / `deploy-fe.ps1`)
- Windows 计划任务 `BatchDeployBE` / `BatchDeployFE`,每 1 min 触发一次
- 脚本逻辑:`git fetch` → 比 `HEAD` 与 `origin/<branch>` → 不同就 `git pull --ff-only` + `docker compose up -d --build --wait`
- 日志:`C:\Users\aa\logs\deploy-{be,fe}.log`
- 锁文件 `deploy-{be,fe}.lock` 防计划任务和手动触发并发(30 min 内复用,避免死锁)

## 为什么这条路

试过的方案对应失败点:
- **GH Actions self-hosted runner + GHCR pull**:18081 代理 EOF/reset 不断,runner 端 git/docker 流量经常被打断
- **GH Actions runner + 部署机本地 build**:仍要 yml 调试(env 透传/.env.local/profile/timeout/dockerignore),debug 路径要走 `gh run view --log-failed`,代理一样挂
- **Portainer CE GitOps**:bind mount 跨 Docker-in-Docker 边界失败(`./scripts/data/init-minio.sh` 这类 mount 在 Portainer 容器内 stack workspace,daemon 看不到)

bash 脚本完全跑在 host,**所有上述跨容器/跨网络问题都不存在**。

## 文件清单

| 路径 | 作用 |
|---|---|
| `C:\Users\aa\scripts\deploy-be.ps1` | BE 自动部署 |
| `C:\Users\aa\scripts\deploy-fe.ps1` | FE 自动部署 |
| `C:\Users\aa\logs\deploy-{be,fe}.log` | 部署日志(无更新时不写,有更新时记 timestamp + git pull + compose 输出) |
| `C:\Users\aa\logs\deploy-{be,fe}.lock` | 并发锁(脚本自管) |

## 计划任务管理

```powershell
# 查看
Get-ScheduledTask -TaskName 'BatchDeploy*'
Get-ScheduledTaskInfo BatchDeployBE   # 末次运行/结果/下次

# 手动触发(测试用)
Start-ScheduledTask -TaskName BatchDeployBE

# 暂停 / 恢复
Disable-ScheduledTask -TaskName BatchDeployBE
Enable-ScheduledTask  -TaskName BatchDeployBE

# 删除重建
schtasks /Delete /TN BatchDeployBE /F
# 注册命令见 docs/runbook/script-deploy.md §安装(或本仓 README)
```

## 排障

**实时盯日志**:
```powershell
Get-Content C:\Users\aa\logs\deploy-be.log -Wait
```

**当前部署 sha**:
```powershell
cd C:\Users\aa\Downloads\file-batch-system; git rev-parse HEAD
```

**强制 redeploy**(代码没变也重建容器):
```powershell
cd C:\Users\aa\Downloads\file-batch-system
docker compose --env-file .env.local `
  -f docker-compose.yml -f docker/compose/app.yml -f docker/compose/app.deploy.yml `
  --profile apps --profile replica `
  up -d --build --force-recreate --wait --wait-timeout 600
```

**回滚到上一版**(脚本不会自动回滚,失败容器停在前一健康态):
```powershell
cd C:\Users\aa\Downloads\file-batch-system
git checkout <prev-sha>
# 再触发 deploy 或直接手动 compose up
```

## 安装(若新机器从零搭)

1. clone BE + FE 仓到 `C:\Users\aa\Downloads\file-batch-system` 和 `C:\Users\aa\Downloads\batch-console`
2. 配 `.env.local`(BE 仓根,gitignored)— 至少含密码 + `SPRING_PROFILES_ACTIVE=dev`(**不要 local**:local profile yml 写死 localhost:15432 给 IDE 直跑用,容器跑会连不上 host postgres)
3. 把 `deploy-be.ps1` / `deploy-fe.ps1` 拷到 `C:\Users\aa\scripts\`
4. 注册计划任务:
   ```powershell
   schtasks /Create /TN BatchDeployBE `
     /TR '"C:\Program Files\PowerShell\7\pwsh.exe" -NoProfile -ExecutionPolicy Bypass -File C:\Users\aa\scripts\deploy-be.ps1' `
     /SC MINUTE /MO 1 /F
   schtasks /Create /TN BatchDeployFE `
     /TR '"C:\Program Files\PowerShell\7\pwsh.exe" -NoProfile -ExecutionPolicy Bypass -File C:\Users\aa\scripts\deploy-fe.ps1' `
     /SC MINUTE /MO 1 /F
   ```
5. 验证:`Start-ScheduledTask BatchDeployBE; Get-Content C:\Users\aa\logs\deploy-be.log -Wait`

## 边界

- **不适合多机部署**(每台都得装脚本)
- **不适合多环境**(prod / staging / dev 分离要复制脚本)
- **没 UI 回滚**(命令行 git checkout + compose up)
- 适合本场景(单机 / 单环境 / 单 owner),团队 / 多环境 → 看 [[../runbook/feature-switches.md]] 或考虑外部 CD
