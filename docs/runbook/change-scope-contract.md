# CI 变更范围契约

## 目的

仓库中的按需 CI 统一使用 `scripts/ci/detect-change-scope.py`。范围探测只回答
“本次变更涉及哪些资产”，各 workflow 再依据自身风险选择扫描；路径规则不应散落在
多个 YAML 文件中。

## 输出域

| 域 | 典型内容 | 典型消费者 |
|---|---|---|
| `java` | Java 主码、POM、Maven wrapper | 编译、PMD、Spotless、Java 单测 |
| `sql` / `database` | Flyway、业务 SQL、Mapper XML | SQL 边界、迁移、RLS、数据库注释 |
| `scripts` | Shell、Python、压测和本地脚本 | ShellCheck、可移植性、脚本治理 |
| `docs` | Markdown、RST、文档目录 | 文档结构、日期策略、链接检查 |
| `config` | env、应用配置、Compose、部署配置 | 配置同步、环境变量和部署守护 |
| `api` | OpenAPI、Controller、codegen | API 路由和破坏性变更检查 |
| `sdk` | 五语言 SDK、共享契约和 fixture | SDK contract/parity/live transport |
| `ci` | workflow、composite action、CI 脚本 | actionlint、zizmor、CI 自检 |
| `tests` | 测试源码、E2E 模块 | 受影响测试和测试覆盖守护 |
| `docker` / `helm` | Dockerfile、镜像编排、Helm | 镜像、容器和生产 overlay 检查 |
| `maven` | POM、`.mvn`、Maven wrapper | 依赖图、版本和构建路由 |
| `unknown` | 未登记的文件类型或路径 | 按代码变更安全回退，并补分类规则 |
| `unit-required` | Maven reactor 源码、迁移、测试和构建文件 | PR gate 的 Maven unit shard |

域可以重叠。`database` 是历史 workflow 的兼容输出，不要删除；新消费者优先使用
更具体的 `sql`、`maven`、`helm` 等域。

## 事件语义

- `pull_request`：使用 `base...head`，探测失败直接失败，不静默跳过。
- `merge_group`、`push`、`schedule`、`workflow_dispatch`：没有可靠 PR diff，输出全域
  `true`，保证发布、合并队列和手工验证不会漏扫。
- `unknown=true` 或非文档域命中：`docs-only=false`。
- `unit-required=true` 或 `unknown=true`：PR gate 必须保留 Maven unit shard；CI、脚本、SDK
  变更可以只走各自专项门禁。
- `docs-only=true` 只表示所有文件都是已识别的文档文件，不表示安全扫描可以跳过。

## 新增路径的流程

1. 先判断它属于已有域还是应新增域。
2. 修改探测器及 `scripts/ci/tests/test_detect_change_scope.py`。
3. 更新本文件的域表和消费 workflow。
4. 运行 `python3 -m unittest scripts/ci/tests/test_detect_change_scope.py` 与
   `actionlint .github/workflows/*.yml`。

探测器不管理 required check；主干规则仍由 GitHub ruleset 约束，`full-ci-gate` 仍保持
全量，不能因为新增范围域而降级为增量。
