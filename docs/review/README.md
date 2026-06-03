# docs/review/

代码深度审查记录(reviewer 视角的快照),区别于 [`../analysis/`](../analysis/README.md) 的架构演进。

## 与其他子目录的分工

| 目录 | 视角 | 例子 |
|---|---|---|
| `review/`(本目录) | **代码级 PR / 模块级深度审查**:bug、坏味道、规约违反、改进点列表 | 单次对 SDK 全模块的 deep-review |
| [`../analysis/`](../analysis/README.md) | **演进向治理**:问题归因 + 修复 + 加固的滚动文档 + 长期治理方案 | hardening backlog / fix report 滚动维护 |
| [`../audit/`](../audit/) | **架构级 audit**:特定时点对单个 bounded context 的结构审视 | console-api-arch-audit-2026-05-23 |

简单判定:
- 单次评 PR / 评模块的实现细节 → `review/`
- 滚动维护问题清单 / 修复计划 → `analysis/`
- 一次性给某模块画架构剖面图 → `audit/`

## 文件清单

| 文件 | 范围 | 一句话 |
|---|---|---|
| `batch-worker-sdk-deep-review-2026-05-31.md` | SDK 三件套(core + starter + testkit) | SDK 全模块代码深度审查,产生改进点列表 |
| `code-review-2026-05-21.md` | 全仓 | 单次代码审查快照 |
| `be-test-consistency-2026-05-21.md` | 全仓测试代码 | 测试一致性专项审查 |

## 维护策略

- 单次审查写完即归档形态,**不滚动维护**;同模块再审 → 新加日期后缀文件,旧的保留对照
- 内容如果转化成"长期改进 backlog",**移到 [`../analysis/hardening-backlog.md`](../analysis/hardening-backlog.md) 或对应 sub-doc**,本目录文件标 fold link
- 文件 > 6 个月且内容已全部落地 → 移 `../archive/review/`(尚无,需要时创建)
