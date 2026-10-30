---
name: configuration-governance
description: 审查或修改配置类、环境变量、功能开关、Helm/Compose、本地配置、动态配置和密钥治理时使用。重点维护配置事实来源、生命周期、环境对齐和漂移守护。
---

# 配置治理

## 配置声明与注入

按 `docs/coding-conventions.md` §3、§13 核对构造器注入、类型安全 `@ConfigurationProperties`、默认值校验及 `@Configuration(proxyBeanMethods = false)`。判断 `@Autowired`/`@Value` 时读取注解位置和装配入口；唯一构造器、显式选择的多构造器入口、配置类 `@Bean` 参数及测试装配不能一概视为字段注入违规，也不能把现行允许的 `@Value` 参数形式改成全局禁令。

## 配置分类

- 为新增或变更配置明确来源、默认值、适用环境、生效方式、是否敏感、是否可动态调整和回滚方式。
- 使用仓库约定的生命周期标签：`STATIC`、`DYNAMIC_DB`、`SECRET`、`RESTART_REQUIRED`。不要把普通属性随意加 `@RefreshScope` 当作热更新方案。
- 密钥、内部 API Key、JWT secret、对象存储凭据等必须 fail-close；生产 Chart/Secret/ConfigMap 要有注入入口，文档要说明生成和轮换方式。
- 动态配置要有版本号、旧版本拒绝、多实例一致性观测和发布后确认；静态/重启生效配置要能触发 Pod 滚动或有明确人工步骤。

## 配置、变量、键与有限域

- 从语义、复用和拥有者决定配置/局部变量/常量的边界；稳定重复配置键和协议键由所属模块维护，有限域优先已有枚举，固定字段优先对象，动态扩展边界保留 Map。
- 核对 key 的命名、大小写、命名空间、序列化及所有消费者。持久化键、API/SDK 字段和环境变量的更名有兼容影响，不在规约整理中直接改变；契约测试保留独立预期，不让实现和期望同时引用同一常量而漏掉漂移。
- 配置和环境抽取同时追本地、容器、CI 与脚本入口；场景特有覆盖值保留，不能把不同业务默认值合并进全局常量袋。

## 对齐范围

1. Java `@ConfigurationProperties`、`application*.yml`、配置校验、默认值和运行时消费方。
2. `.env.example`、Docker Compose、Helm values、ConfigMap/Secret、CI 白名单和本地脚本。
3. Console 配置维护页面、导入/导出模板、说明文案和 OpenAPI 契约。
4. 文档中的配置表、运行手册、生产覆盖示例和压测/场景测试 profile。

## 守护与验证

- 配置类或开关变化后，优先运行仓库已有同步检查：`check-config-defaults-sync.py`、`check-feature-switch-registry.py`、`check-helm-env-sync.py`、`check-env-variable-governance.py`、`check-production-overlay-safety.py`、`check-config-governance.py`。
- 检查脚本的输出格式要统一，便于 CI 和本地预检解析。
- 脚本访问 Console、Orchestrator 等平台服务时，从 `scripts/lib/env-common.sh` 读取公共地址；不要在观测或验收脚本另设同一服务的默认地址。`check-hardcoded-runtime-config.sh` 负责拦截已登记的平台地址边界，检查增减时同步 `docs/runbook/ci.md` 与守卫矩阵。
- 运行配置门禁结果通过统一 runner 输出，按 CI 所在策略组归类；脚本诊断与门禁最终状态行分开维护。
- 环境变量新增后确认本地、容器、Helm、CI 和文档是否全部覆盖；只在单一环境可用的变量要注明边界。
- 不把压测临时参数混入默认生产配置；需要实验值时使用独立 benchmark profile 或脚本参数。

## 报告口径

结论要说明配置是否已对齐、哪些环境已覆盖、是否需要滚动重启、是否涉及密钥、未验证的运行时生效路径以及后续压测/演练需求。

## 当前文档与脚本的方法补充

追配置实际生效、配置导入或密钥版本对在途任务的影响时，按需读取[配置消费、导入与密钥生命周期](references/lifecycle-methods.md)。只加载相关方法，不把文档列出的操作自动加入执行范围。
