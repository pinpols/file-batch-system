# 发布与部署脚本

本目录只保存消费已构建制品的发布工具，不在部署机执行 Maven 或 npm 构建。

- `release_manifest.py`：校验统一 release manifest、从 Docker Bake metadata 提取后端
  digest，合并前端 digest，并生成 Compose 或 Helm GitOps 不可变镜像覆盖层。
- `compose-release.sh`：按完整前后端 manifest 执行 Compose 预检、拉取、启动、健康与 digest 校验；
  失败时尝试恢复上一份稳定 manifest。
- `.github/workflows/compose-cd.yml`：手动固定前后端 main SHA，复用镜像构建工作流；可选部署
  staging，并在部署 smoke、Playwright 或 Lighthouse 失败时恢复上一稳定 release set。
- `render-helm-values` 子命令：生成 Helm `image.references` 逐镜像 digest values；不会写入环境配置或 Secret。

GitOps ops 仓模板见 `deploy/gitops/ops-repo-template/`；真实 promotion PR、Argo CD 同步和
staging/production 主机仍需外部环境接入。Compose CD 的配置项和激活步骤见
`docs/runbook/compose-cd-roadmap.md`。
