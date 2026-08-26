# Java 语义表达扫描记录（2026-08-26）

> 本次扫描遵循 [代码规范](../coding-conventions.md) §1.1、§1.4、§1.5，不以消灭 `&&` / `||` 或 `new` 语句为目标。

## 范围与方法

- 范围：全仓 2,202 个生产 Java 源文件；不扫描测试 fixture、生成目录和声明式配置。
- 复合条件：使用 JavaParser 统计 `if` 条件中至少三个逻辑原子项，再由人工按业务语义裁定。
- 内联对象：扫描作为另一个方法实参的 `new XxxCommand/Request/Query/Context/Options/Payload(...)`，并核对 builder 调用现场。

## 本轮结果

| 项目 | 扫描前 | 扫描后 | 裁定 |
|---|---:|---:|---|
| 三项以上复合 `if` | 317 | 308 | 已提取 9 个业务状态/准入判断；其余为合理保留 |
| 直接传参的领域对象构造 | 35 | 29 | 已处理高参数 REST 请求、分页 Query、配额预留和 retry 查询 |

## 已处理的表达

- Console：文件操作和审批请求先构造成具名局部变量；AI 审计、文件到达组/错误记录、工作流定义/节点/边等分页查询复用同一个 Query 对象，避免 `select/count` 的条件漂移。
- Orchestrator：lease 回收、retry 扫描、Redis 配额降级和租户/队列分区配额预留均使用具名查询/命令；启动残留审计、分区准入条件表达为业务事实。
- Worker / SDK：阶段幂等跳过、process 插件选择、Kafka consumer 恢复和上报客户端错误归因使用命名局部布尔，未改变短路顺序、Kafka offset、事务或重试语义。
- Trigger：catch-up pending 与 request 的关联前置条件显式命名。

## 合理保留的候选

| 类别 | 数量 | 保留原因 |
|---|---:|---|
| `security-scan` 的 `ExternalCommand` | 5 | `switch` 内的声明式扫描步骤，不是业务调用链 |
| observability 的无参 query request | 11 | 无参数对象只作为 trace 载体，额外变量不会增加信息 |
| 短 transport payload | 6 | 1～3 个字段，如 claim / renew / catch-up，按 §1.1 保持 inline |
| 内部工厂或适配器组合 | 5 | 审批静态工厂、Servlet request wrapper、批处理数据映射；工厂/适配器本身就是命名边界 |
| Controller 响应元素映射 | 2 | collection 内 immutable DTO 映射，不承担命令或跨层上下文语义 |

其余 308 个复合条件主要是 null/type guard、协议/SQL/IP 解析、范围/位运算、白名单分类或短路防御。强制拆成 `boolean` 会遮蔽校验顺序，属于本项目明确禁止的机械变量化。

## 防漂移

- 新增业务状态或准入条件时，按 [代码规范](../coding-conventions.md) §1.5 使用能读出业务事实的局部变量，禁止 `isOk`、`flag` 等泛名。
- 新增高参数 `Command` / `Context` / `Param` 作为调用实参时，遵循 §1.1 与 §1.4；既有 `PositionalArgsConventionTest` 继续守护已治理类型。
- 每次修改生产 Java 后运行 `python3 scripts/ci/report-java-readability-inventory.py --check docs/analysis/java-readability-inventory-2026-08-12.md`，保持机器快照与源码一致。
