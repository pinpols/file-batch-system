---
name: configuration-governance
description: 审查或修改配置类、环境变量、功能开关、Helm/Compose、本地配置、动态配置和密钥治理时使用。重点维护配置事实来源、生命周期、环境对齐和漂移守护。
---

# 配置治理

## 配置分类

- 为新增或变更配置明确来源、默认值、适用环境、生效方式、是否敏感、是否可动态调整和回滚方式。
- 使用仓库约定的生命周期标签：`STATIC`、`DYNAMIC_DB`、`SECRET`、`RESTART_REQUIRED`。不要把普通属性随意加 `@RefreshScope` 当作热更新方案。
- 密钥、内部 API Key、JWT secret、对象存储凭据等必须 fail-close；生产 Chart/Secret/ConfigMap 要有注入入口，文档要说明生成和轮换方式。
- 动态配置要有版本号、旧版本拒绝、多实例一致性观测和发布后确认；静态/重启生效配置要能触发 Pod 滚动或有明确人工步骤。

## 对齐范围

1. Java `@ConfigurationProperties`、`application*.yml`、配置校验、默认值和运行时消费方。
2. `.env.example`、Docker Compose、Helm values、ConfigMap/Secret、CI 白名单和本地脚本。
3. Console 配置维护页面、导入/导出模板、说明文案和 OpenAPI 契约。
4. 文档中的配置表、运行手册、生产覆盖示例和压测/场景测试 profile。

## 守护与验证

- 配置类或开关变化后，优先运行仓库已有同步检查：`check-config-defaults-sync.py`、`check-feature-switch-registry.py`、`check-helm-env-sync.py`、`check-env-variable-governance.py`、`check-production-overlay-safety.py`、`check-config-governance.py`。
- 检查脚本的输出格式要统一，便于 CI 和本地预检解析。
- 环境变量新增后确认本地、容器、Helm、CI 和文档是否全部覆盖；只在单一环境可用的变量要注明边界。
- 不把压测临时参数混入默认生产配置；需要实验值时使用独立 benchmark profile 或脚本参数。

## 报告口径

结论要说明配置是否已对齐、哪些环境已覆盖、是否需要滚动重启、是否涉及密钥、未验证的运行时生效路径以及后续压测/演练需求。
