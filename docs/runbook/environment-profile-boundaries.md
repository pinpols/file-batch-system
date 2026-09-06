# 环境配置边界

## 目标

环境差异只能在一个明确的所有者处定义，避免同一个参数同时被 Spring profile、Compose 默认值、`.env.local` 和 Helm 覆盖。

## 分层规则

| 层 | 责任 | 禁止放入 |
|---|---|---|
| `application.yml` | 跨环境安全默认值与参数语义 | 本机端口、压测容量、真实密钥 |
| `application-local.yml` | IDE/本机拓扑、开发旁路 | 压测吞吐、生产副本和连接池预算 |
| `application-benchmark.yml` | 隔离容量画像的资源预算 | 生产默认、密钥、业务功能开关 |
| `.env.local` / `.env.test` / `.env.prod` | 凭据、端口、外部服务地址和环境身份 | 代码中的性能调优默认值 |
| `docker/compose/app.yml` | 容器网络、卷、密钥传递和服务编排 | profile 已定义的容量数字 |
| `docker/compose/benchmark.yml` | 选择 benchmark profile | 重复的 Spring 容量参数 |
| Helm `values*.yaml` | Kubernetes 部署容量与生产运行参数 | 本机开发或压测基线 |

## 已定义环境

- `local`：日常本机/Compose 联调。使用安全基础预算，不自动启用压测参数。
- `benchmark`：仅对 Trigger 生效，必须与 `local` 组合启用。当前容量画像为入口 `32`、Hikari `40`、后台预留 `8`、有界队列 `128`、等待 `15s`。
- `ci`：CI 由工作流注入连接和密钥，不复用 `local` 的 localhost 与安全旁路。
- `prod`：由 Helm values/Secret 注入，不得启用 `local`、`benchmark` 或 bypass。

## 操作入口

日常本地服务：

```bash
./scripts/docker/up-apps.sh
```

隔离 Trigger 容量压测：

```bash
COMPOSE_BENCHMARK=1 ./scripts/docker/up-apps.sh trigger
bash load-tests/scripts/run-p2-capacity-profile.sh
```

压测脚本会核验容器 profile、入口许可、连接池和启动期保留连接预算；任一项不符即停止。

## 变更守则

1. 新增环境专用参数先确定唯一所有者，再添加配置；不得通过 `.env.local` 临时覆盖并形成隐式基线。
2. 影响运行语义的开关登记到 `docs/runbook/feature-switch-registry.yml`；容量数字不是功能开关，不登记为公共 Compose 透传变量。
3. 同时修改 Spring 与 Helm 默认值时运行 `check-config-defaults-sync.py`、`check-helm-env-sync.py` 和 `helm lint`。
4. 容量结论必须在 `benchmark` profile 的报告中记录，不能据日常 `local` 运行直接推导生产承诺。
