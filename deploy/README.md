# 部署资源

本目录保存部署时消费的镜像构建文件、清单、模板与发布契约。执行脚本按职责放在 `scripts/`，应用 Helm Chart 与 values 统一放在 [helm/](../helm/README.md)。

| 入口 | 资源 / 使用方式 |
|---|---|
| [docker/](docker/README.md) | Dockerfile、镜像启动入口、数据库初始化与本地测试/观测资源；构建启停见 [scripts/docker](../scripts/docker/README.md) |
| [gitops/ops-repo-template/](gitops/ops-repo-template/README.md) | 独立运维仓库模板及 Argo CD 配套资源；promotion / release 入口见 [scripts/deploy](../scripts/deploy/README.md) |
| [ha/](ha/README.md) | Kubernetes 基础设施 Operator / HA 清单模板；使用前按目标集群配置并执行对应运维流程 |
| [release/release-manifest.schema.json](release/release-manifest.schema.json) | 不可变发布清单契约；示例见 [release-manifest.example.json](release/release-manifest.example.json)，验证与发布见 [scripts/deploy](../scripts/deploy/README.md) |

根 `docker-compose*.yml` 与 [容器部署手册](../docs/runbook/docker-deployment.md)承接本地/隔离测试入口；生产 Kubernetes 使用 Helm 基础 Chart、生产策略与站点私有 values。这里的模板需要按目标环境填写和验证，目录整理本身不执行部署。通用工程治理配置见 [config/](../config/README.md)。
