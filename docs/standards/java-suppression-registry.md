# Java Suppression 登记表

> 维护范围：生产 Java（`src/main/java`）。测试代码的局部 `unchecked` 等 fixture 例外不进入本表。
> 机器校验：`python3 scripts/ci/check-java-suppression-registry.py`。

这不是鼓励增加 suppression 的白名单。每个新增例外都必须先确认能否通过类型、结构或安全校验消除；确实不能消除时，才登记规则、模块归属、保留原因和后续移除条件。检查脚本使用精确规则表，未登记的规则会阻断 CI。

## 当前规则归属

| 规则 | 当前用途 | 主要归属 | 移除条件 |
|---|---|---|---|
| `unchecked`, `rawtypes` | Jackson/JSONB/Redis/动态 JSONB payload 的边界强转 | common、orchestrator、worker、SDK | 改为 typed DTO/`TypeReference`，且不改变动态扩展字段契约 |
| `PMD.ExcessiveParameterList` | record、MyBatis 投影、Spring 构造注入和稳定 SPI 参数顺序 | common、console、orchestrator、SDK | 引入上下文对象后仍能保持语义清晰，并补齐构造/契约测试 |
| `PMD.NcssCount` | Excel 工作流拓扑校验的一次性图算法 | console | 拆分后仍能保持同一校验事务和中间状态可见性 |
| `java:S112` | import/export/process/dispatch/atomic 插件 SPI 对外暴露的通用异常契约 | common、SDK、worker import | SPI 改为稳定的领域异常契约并完成五语言 SDK 兼容验证 |
| `java:S1181` | SDK Kafka consumer 的边界兜底，保证 listener 不因不可预期错误中断 | SDK | listener 容器具备等价的统一错误边界和重试语义 |
| `java:S1313` | SSRF 防护内置的云元数据地址阻断规则 | worker atomic | 安全规则改由同等强度的集中策略提供 |
| `java:S2068` | 仅用于检测出厂默认密码常量的安全守卫 | console | 默认凭据改为首次 provision 随机生成且无兼容保留 |
| `java:S2077` | 动态 SQL/存储过程名称仅在 allowlist、schema 和固定片段校验后执行 | orchestrator、worker | 全部调用改为参数化且不再需要动态标识符 |
| `java:S2259`, `java:S2583`, `java:S2589` | Spring/Lombok/配置驱动的可达性或空值分析误报 | common、console、orchestrator、worker、trigger | 静态分析能识别实际控制流，或代码改为显式判定 |
| `java:S3330`, `java:S4502` | Cookie/CSRF 与内部无状态链路的安全配置由代码显式承担 | console、trigger | 安全配置迁移到等价且可审计的框架配置 |
| `java:S6218` | Java record/数组/协议载荷的不可避免参数形态 | console、orchestrator、worker、SDK | 改为命名对象且不降低传输契约可读性 |
| `ConfigurationProperties` | 多个 properties 类型共享同一前缀但子键不重叠 | common、worker | 前缀拆分或 Spring 元数据能准确表达同一配置边界 |
| `SpringJavaInjectionPointsAutowiringInspection` | Spring 自动配置的条件 bean / `ObjectProvider` 注入点 IDE 误报 | common | IDE 与 Spring Boot 配置元数据无误报 |
| `deprecation` | 预留但尚未启用的 `DistributedLock` 配套切面 | common | 该预留 SPI 删除，或正式采用并移除 deprecated 标记 |

## 审核规则

1. suppression 必须贴近实际触发点，优先方法/局部变量，禁止为了省事加到整个类或包。
2. 安全规则（`S1313`、`S2068`、`S2077`、`S3330`、`S4502`）不得用 suppression 掩盖校验缺失；必须能在相邻代码或安全测试中找到正向守护。
3. `unchecked` 只允许出现在动态 JSON/Map/扩展字段边界；生产 API 和核心状态机不得用它掩盖 DTO 漂移。
4. 修改现有 suppression 时，必须重新跑 PMD、Spotless、受影响模块测试和本检查脚本。
5. 删除代码时同步删除对应 suppression；本阶段已移除 `DistributedLockAspect` 中无运行时用途的 `SimpleLock` 编译守卫。

## 复查命令

```bash
python3 scripts/ci/check-java-suppression-registry.py
python3 scripts/ci/report-java-readability-inventory.py
./mvnw -DskipTests test-compile pmd:check spotless:check -fae -B
```
