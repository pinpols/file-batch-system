# BE 验收 backlog — 2026-05-21

> 由 `scripts/local/be-acceptance.sh` 自动生成。请补充失败明细后归档。

## 工具链 / 环境(不修主代码)
- [ ] (e.g. Mockito 5.20 + JDK 25 mock interface 失败)
- [ ] (e.g. ReadReplicaIT 缺 docker compose replica)

## 他人 commit 引入违约(交给作者)
- [ ] (e.g. WorkerLeaseProperties test FQN 6 处)

## 我自己未修完
- [ ] (本次 PR 范围外的违约/技术债)

## 性能 / 健壮性(独立 PR)
- [ ] (e.g. Compensation stale-RUNNING reconciler 兜底)

---

## 关联日志(本次跑)
- /Users/dengchao/Downloads/file-batch-system/logs/be-acceptance/step5-strict.log

## gh CLI dry-run(确认后自己 push)
```bash
# 把本文件作为 issue body
gh issue create --title "BE 验收 backlog 2026-05-21" --body-file "docs/backlog/be-acceptance-2026-05-21.md" --label "backlog,be-acceptance"
```
