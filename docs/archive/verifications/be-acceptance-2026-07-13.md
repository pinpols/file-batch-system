> 历史原始记录：本轮恢复保留，正文中的结论、参数、状态和操作步骤仅适用于原日期/基线。提炼方法不替代原始证据；当前事实须按[文档治理](../../standards/document-governance.md)重新核实。本轮未执行原测试或扫描。

# BE 验收 backlog — 2026-07-13

> 由 `scripts/local/be-acceptance.sh` 自动生成。请补充失败明细后归档。

## 工具链 / 环境(不修主代码)
- [ ] (e.g. Mockito javaagent / fork JVM 失败)
- [ ] (e.g. ReadReplicaIT 缺 docker compose replica)

## 他人 commit 引入违约(交给作者)
- [ ] (e.g. WorkerLeaseProperties test FQN 6 处)

## 我自己未修完
- [ ] (本次 PR 范围外的违约/技术债)

## 性能 / 健壮性(独立 PR)
- [ ] (e.g. Compensation stale-RUNNING reconciler 回退)

---

## 关联日志(本次跑)
- /Users/dengchao/Downloads/file-batch-system/logs/runs/be-acceptance/be-acceptance-20260713-102219-671f90f5a/01-build-maven.log
- /Users/dengchao/Downloads/file-batch-system/logs/runs/be-acceptance/be-acceptance-20260713-102219-671f90f5a/01-restart-console.log
- /Users/dengchao/Downloads/file-batch-system/logs/runs/be-acceptance/be-acceptance-20260713-102219-671f90f5a/05-strict-verify.log
- /Users/dengchao/Downloads/file-batch-system/logs/runs/be-acceptance/be-acceptance-20260713-102219-671f90f5a/07-fe-build.log

## gh CLI dry-run(确认后自己 push)
```bash
# 把本文件作为 issue body
gh issue create --title "BE 验收 backlog 2026-07-13" --body-file "docs/archive/verifications/be-acceptance-2026-07-13.md" --label "backlog,be-acceptance"
```
