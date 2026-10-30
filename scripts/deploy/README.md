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

## 脚本与配套 SQL 索引

本表登记本目录的执行入口、公共模块及配套 SQL；测试代码通过测试命令执行。新增、移动或删除资产时同步跳转和用途，SQL 正文保持独立维护。

| 文件 | 用途 / 调用归属 |
|---|---|
| [compose-release.sh](compose-release.sh) | 按不可变 release manifest 部署后端 Compose 服务，并在失败时恢复上一稳定版本。 |
| [release_manifest.py](release_manifest.py) | 校验发布清单、生成 Compose 覆盖层并提取后端镜像 digest。 |
