# 配置键字典（自动生成）

> **不在仓库维护人读副本**。配置键 dict 的权威来源是 Spring Boot 编译时生成的 `META-INF/spring-configuration-metadata.json`。

## 生成机制

`spring-boot-configuration-processor` 已挂入根 pom 的 `maven-compiler-plugin` annotationProcessorPaths。每次 `mvn compile` 自动扫描所有 `@ConfigurationProperties` 类，产出：

```
batch-common/target/classes/META-INF/spring-configuration-metadata.json
batch-orchestrator/target/classes/META-INF/spring-configuration-metadata.json
batch-trigger/target/classes/META-INF/spring-configuration-metadata.json
batch-worker-import/target/classes/META-INF/spring-configuration-metadata.json
batch-worker-export/target/classes/META-INF/spring-configuration-metadata.json
batch-worker-dispatch/target/classes/META-INF/spring-configuration-metadata.json
batch-console-api/target/classes/META-INF/spring-configuration-metadata.json
```

## 怎么用

### 1. 写 yml / properties — IDE 自动补全

任何识别 Spring Boot metadata 的 IDE（IntelliJ IDEA / VS Code with Spring Boot Extension Pack / Cursor）都会：

- yml 中输入 `batch.` → 自动列出所有可用键
- 鼠标悬停 → 显示字段 javadoc + 默认值 + 类型

### 2. 命令行查询

```bash
# 看 batch-common 模块的所有键
mvn -pl batch-common compile -q
jq -r '.properties[].name' batch-common/target/classes/META-INF/spring-configuration-metadata.json | sort

# 看某个前缀（如 batch.security）的所有键 + 默认值
jq '.properties[] | select(.name | startswith("batch.security"))' \
   batch-common/target/classes/META-INF/spring-configuration-metadata.json
```

### 3. 全模块汇总

```bash
mvn compile -q
find . -name spring-configuration-metadata.json -path '*/target/*' \
  -exec jq -r '.properties[].name' {} \; | sort -u
```

## 描述字段写法

字段加 javadoc → 自动进 metadata 的 `description`：

```java
@ConfigurationProperties(prefix = "batch.security")
public class BatchSecurityProperties {

  /**
   * 全链路安全旁路总开关。开启后认证 / 脱敏 / 加解密 / 审批 / 渠道校验全部放行。
   * 仅用于本地 / 联调 / E2E。生产 profile 强制拒绝 true。
   */
  private boolean bypassMode;
}
```

**给字段加 javadoc 是描述配置键的唯一推荐方式**——比手写 markdown 准确、永不漂移、IDE 实时可见。

## 全局规约

- 配置键命名：`batch.<module>.<feature>.<param>` 全小写 kebab-case
- 默认值：在 java 字段直接初始化（让 metadata 自动捕获）
- 不要给 sensitive 字段加 javadoc 暴露密钥来源细节

## 配套文档

- 散文档怎么用配置：[`../runbook/feature-switches.md`](../runbook/feature-switches.md) Phase 2 全部能力开关
- 安全旁路总开关：[`../coding-conventions.md`](../coding-conventions.md) §21
- 时区开关：CLAUDE.md §时区策略 + `BatchTimezoneProperties`
