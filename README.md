# File Batch System

## 文档入口

- 设计文档：[批量调度系统设计说明书（完整版）-20260321.md](/Users/dengchao/Downloads/file-batch-system/批量调度系统设计说明书（完整版）-20260321.md)
- 工程基线：[AGENT.md](/Users/dengchao/Downloads/file-batch-system/AGENT.md)
- 本地环境：[docs/local-development.md](/Users/dengchao/Downloads/file-batch-system/docs/local-development.md)
- Docker 部署：[docs/deployment/docker-deployment.md](/Users/dengchao/Downloads/file-batch-system/docs/deployment/docker-deployment.md)
- 运行时模块通信：[docs/architecture/runtime-module-communication.md](/Users/dengchao/Downloads/file-batch-system/docs/architecture/runtime-module-communication.md)
- 设计差距审计：[docs/architecture/design-gap-audit.md](/Users/dengchao/Downloads/file-batch-system/docs/architecture/design-gap-audit.md)
- 默认运行参数基线：[docs/architecture/runtime-default-parameters.md](/Users/dengchao/Downloads/file-batch-system/docs/architecture/runtime-default-parameters.md)
- 测试策略：[docs/testing/test-strategy.md](/Users/dengchao/Downloads/file-batch-system/docs/testing/test-strategy.md)
- 系统测试种子：[docs/sql/system-test/README.md](/Users/dengchao/Downloads/file-batch-system/docs/sql/system-test/README.md)
- 对话补全过程：[补全对话.md](/Users/dengchao/Downloads/file-batch-system/补全对话.md)
- 历史补全记录：[后续对话step.md](/Users/dengchao/Downloads/file-batch-system/后续对话step.md)

## 当前状态

- 主链已经收口到 `DB -> Outbox -> Kafka -> CLAIM -> EXECUTE -> REPORT`
- 本地依赖基线是 PostgreSQL + Kafka + MinIO
- 系统测试的数据和 SQL 已整理到 `docs/sql/system-test/`
- 测试建议按单元 / 集成 / 端到端三层推进，见 [docs/testing/test-strategy.md](/Users/dengchao/Downloads/file-batch-system/docs/testing/test-strategy.md)

## 快速校验

```bash
mvn -q compile
```

## 系统测试

优先使用这套入口加载种子和样本文件：

```bash
scripts/local/load-system-test-data.sh
```

需要手动执行时，先看 [docs/sql/system-test/README.md](/Users/dengchao/Downloads/file-batch-system/docs/sql/system-test/README.md)。
