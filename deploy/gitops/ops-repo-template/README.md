# Batch Platform GitOps Repository Template

此目录是外部 ops 仓库的起始模板，不代表本项目已连接 Kubernetes、Argo CD 或外部 Secret 后端。

## Staging

1. 复制 `argo/application-staging.yaml` 与 `environments/staging/` 到独立 ops 仓库。
2. 在 `environments/staging/values.yaml` 配置集群地址、非敏感环境参数和预先创建的 `security.existingSecretName`。凭据只交给外部密钥控制器，不写入 Git。
3. 从经过验证的 release manifest 生成不可变镜像覆盖文件：

   ```bash
   python3 scripts/deploy/release_manifest.py render-helm-values release-manifest.json \
     --output /path/to/ops-repo/environments/staging/release-images.yaml
   ```

4. 提交并审查 ops 仓库变更，再由 Argo CD 同步。staging smoke、Secret 控制器就绪和集群同步仍需在目标环境验收。

`release-images.yaml` 只包含后端逐镜像 digest 和 manifest 对应的 `release.backendCommit`，不覆盖环境配置。promotion 变更还必须把 Argo Application 的 Chart `targetRevision` 更新为该 commit，保证 Chart 模板与镜像来自同一后端版本。工具拒绝缺失或可变 tag 的 release manifest；Helm helper 也要求引用值使用 `@sha256:`。

生产环境使用单独的 Application 和 values 文件；模板不启用自动同步。生产审批、回滚和 Secret 轮换由运维流程控制。
