# 发布与部署脚本

本目录只保存消费已构建制品的发布工具，不在部署机执行 Maven 或 npm 构建。

- `release_manifest.py`：校验统一 release manifest、从 Docker Bake metadata 提取后端
  digest，并生成 Compose 不可变镜像覆盖层。
- `compose-release.sh`：按 manifest 执行 Compose 预检、拉取、启动、健康与 digest 校验；
  失败时尝试恢复上一份稳定 manifest。

完整流程和外部环境要求见 `docs/runbook/compose-cd-roadmap.md`。
