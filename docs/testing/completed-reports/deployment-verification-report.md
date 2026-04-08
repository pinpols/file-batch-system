# Phase 5 部署升级与回滚验证报告

更新时间：2026-03-28

## 结论

部署升级 / 回滚验证的执行路径已经补齐到统一回归脚本中，支持以独立模式进行 staging 执行：

```bash
BATCH_DEPLOY_VERIFICATION_ENABLE_LIVE=true \
bash scripts/ci/run-full-regression.sh --with-deployment-verification
```

当前已完成的是验证工具链与脚本入口收口；真实 staging 集群上的 live upgrade / rollback 留档仍需在目标环境执行。

## 已补齐的能力

- `scripts/ci/run-full-regression.sh` 新增 `--with-deployment-verification`
- 通过 Helm release revision 进行升级和回滚验证
- 通过 `podAnnotations.rollbackVerificationRunId` 触发一次可观测的滚动升级
- 回滚后重新检查 readiness，确认回到上一版本
- 部署验证可以使用独立 release / namespace，避免污染普通 deploy smoke

## 验证流程

脚本执行顺序如下：

1. `helm lint`
2. `helm template`
3. 初次 `helm upgrade --install --wait`
4. 部署 readiness 校验
5. 通过 annotation 触发二次升级
6. 再次 readiness 校验
7. `helm rollback` 回到 revision 1
8. 再次 readiness 校验
9. 断言回滚后 annotation 已消失

## 建议的 staging 执行方式

```bash
export BATCH_DEPLOY_VERIFICATION_ENABLE_LIVE=true
export BATCH_DEPLOY_VERIFICATION_RELEASE=batch-platform-verification
export BATCH_DEPLOY_VERIFICATION_NAMESPACE=batch-verification
export BATCH_DEPLOY_VERIFICATION_VALUES_FILE=helm/values-prod.yaml

export BATCH_DEPLOY_VERIFICATION_PLATFORM_DB_PASSWORD='***'
export BATCH_DEPLOY_VERIFICATION_BUSINESS_DB_PASSWORD='***'
export BATCH_DEPLOY_VERIFICATION_MINIO_ACCESS_KEY='***'
export BATCH_DEPLOY_VERIFICATION_MINIO_SECRET_KEY='***'

bash scripts/ci/run-full-regression.sh --with-deployment-verification
```

## 放行证据

执行完成后，应保留：

- 终端日志
- `helm history` 输出
- `kubectl get deploy,pod,svc,ingress,hpa -o wide`
- `kubectl describe` 和关键容器日志
- 使用的 values 文件版本和镜像 tag
- 执行时间、执行人、context、namespace、release 名称

## 当前残余缺口

- 真实 staging 集群上的 live 执行结果尚未回填
- 回滚后的业务链路级验收仍需按发布流程补充
- 数据迁移前后兼容窗口仍需按发布节奏验证

## 相关文档

- `docs/testing/full-project-test-plan.md`
- `docs/testing/phase-1-test-coverage-matrix.md`
- `docs/testing/release-gate.md`
- `docs/testing/staging-live-deploy-smoke-checklist.md`
