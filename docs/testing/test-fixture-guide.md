# 测试数据归属与使用

> 类型：长期测试方法资产；直接维护原文。场景数据位于根 test-data/，本页只维护用途、入口与验证方法。

| 资产 | 位置 | 使用方式 |
|---|---|---|
| 脚本/多模块共享 SQL、JSON 场景数据 | [test-data/sim/](../../test-data/sim/) | SIM 脚本引用，SQL 与 Shell 编排分离；本目录的数据准备不替代被测 API/Worker 行为 |
| 租户配置包 XLSX | [test-data/tenant-config-packages/](../../test-data/tenant-config-packages/README.md) | append-tenant-coverage.py、gen-default-tenant-excel.py 生成；fix-fixture-xlsx.py --check 检查 |
| 模块专用 classpath 资源 | 所属模块 src/test/resources/ | 由该模块测试加载 |
| 跨模块 classpath 共享资源 | batch-test-support | 沿用现有共享测试模块，不另建通用 fixture 框架 |
| API 黄金样本 | docs/api/sdk-contract-fixtures/ | 属于协议契约资产，按 SDK conformance 规则维护 |

移动数据时同步脚本、生成器、Java/Python 契约测试、pre-commit 和 CI 路由；仅迁移路径时校对文件 SHA-256，不能借目录整理更改 SQL、JSON 或工作簿内容。初始化、数据库写入或完整 SIM 执行按任务授权和验收流程进行。

只读检查示例：

```bash
bash scripts/python.sh scripts/fix-fixture-xlsx.py --check
python3 -m unittest scripts/ci/tests/test_config_package_fixture_generators.py
python3 -m unittest scripts/ci/tests/test_detect_change_scope.py
```

原始本地验收问题产物输出到 reports/acceptance/；整理后的验收结论进 docs/verifications/。每轮记录不能代替当前工作台账和系统资产更新。
