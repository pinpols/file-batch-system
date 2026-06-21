# Spike:Resilience4j 在 Spring Boot 4 / JDK 25 上的兼容性

**日期**:2026-05-30
**对应 epic**:P1-B Phase 2(从 `DownstreamFallback` 升级到 Resilience4j circuit-breaker)
**结论**:✅ **可行**。R4J 2.3.0 在 SB 4.0.6 + JDK 25 上能加载 autoconfig、注册 bean、状态机正常工作、Micrometer 自动埋点正常。

---

## 背景

`docs/runbook/downstream-degradation.md` 末尾留了 Resilience4j 升级路径,但 SB 4.0.6 兼容性未知:

- R4J 1.7.x 已停维(2021)
- R4J 2.x / 3.x 文档面向 Spring Boot 3.x(Spring Framework 6)
- 我们用的是 SB 4.0.6(Spring Framework 7,jakarta namespace)

本 spike 用最小 `@SpringBootTest` 验证能否跑通。

## 验证版本

| 维度 | 版本 |
|---|---|
| Spring Boot | 4.0.6 |
| Spring Framework | 7.x(SB 4 默认) |
| JDK | 25 |
| Resilience4j | 2.3.0(maven central 最新,2025-01) |
| 依赖 | `resilience4j-spring-boot3` + `resilience4j-micrometer` |
| 传递依赖 | `resilience4j-spring6`(Spring 框架 6 接口实现层) |

## 实施

### 加入 dependencyManagement(根 pom.xml)

```xml
<resilience4j.version>2.3.0</resilience4j.version>
...
<dependency>
  <groupId>io.github.resilience4j</groupId>
  <artifactId>resilience4j-spring-boot3</artifactId>
  <version>${resilience4j.version}</version>
</dependency>
<dependency>
  <groupId>io.github.resilience4j</groupId>
  <artifactId>resilience4j-micrometer</artifactId>
  <version>${resilience4j.version}</version>
</dependency>
```

### batch-console-api 加直接依赖

```xml
<dependency>
  <groupId>io.github.resilience4j</groupId>
  <artifactId>resilience4j-spring-boot3</artifactId>
</dependency>
<dependency>
  <groupId>io.github.resilience4j</groupId>
  <artifactId>resilience4j-micrometer</artifactId>
</dependency>
```

### 验证测试

`batch-console-api/src/test/java/.../spike/Resilience4jSb4CompatSpike.java`(89 行)
用 `@ImportAutoConfiguration` 精确导 R4J 的 4 个核心 autoconfig,避开本项目 batch-common 的
autoconfig(它们要 Clock / InformationSchemaMapper / DataSource 等无关 bean):

```java
@ImportAutoConfiguration({
    CircuitBreakerAutoConfiguration.class,
    CircuitBreakerMetricsAutoConfiguration.class,
    RetryAutoConfiguration.class,
    RetryMetricsAutoConfiguration.class,
})
```

## 验收结果

| 验收点 | 结果 |
|---|---|
| SB4 启动加载 R4J autoconfig 不报 `NoSuchMethodError` / `NoClassDef` / `IncompatibleClassChange` | ✅ |
| `CircuitBreakerRegistry` / `RetryRegistry` bean 注册 | ✅ |
| `application` 属性配置的 instance 被读到(failure-rate-threshold / sliding-window-size / max-attempts) | ✅ |
| 状态机失败累计 → OPEN(50% 阈值 + 窗口 4 + 4 次强制失败) | ✅ |
| Micrometer 自动埋点出现(`resilience4j.circuitbreaker.*`) | ✅ |
| `@CircuitBreaker` 注解 AOP 切面命中 RestClient | ⏳ **未验** — 需起 Web 上下文,留独立 PR |

**测试输出**:`Tests run: 4, Failures: 0, Errors: 0`(8.1s)

## 遇到问题记录

### 1. logback `FRONTEND_FILE` appender 绑死 `/logs/`

最小 `@SpringBootTest` 默认无 profile,触发 logback `<springProfile name="!local & !test & !e2e">` 分支,
尝试创建 `/logs/frontend.log`(本机无写权限)→ 启动 fail。

**修法**:`@ActiveProfiles("test")`。

### 2. batch-common 的 `@AutoConfiguration` 链拽过大

默认 `@SpringBootApplication` 触发 `BatchStartupSelfCheckAutoConfiguration` 要
`InformationSchemaMapper`(MyBatis),`BatchTimezoneAutoConfiguration` 要 `java.time.Clock` bean。

**修法**:不用 `@SpringBootApplication`,改用 `@SpringBootConfiguration` + `@ImportAutoConfiguration`
明确只导 R4J 4 个 autoconfig。spike 不验本项目业务路径,只验 R4J 本身。

### 3. Spring 6 vs Spring 7 API surface 未爆雷

预期最大风险:`resilience4j-spring6` 模块用 Spring 6 接口,可能在 Spring 7 下方法签名漂移。
**实际**:核心路径(`@Aspect` / `BeanPostProcessor` / `ConfigurationProperties`)在 Spring 6→7 没断,
R4J 的反射依赖面不大,所以无碰撞。

## 后续工作(独立 PR / epic)

### Phase 2-A:补 `@CircuitBreaker` 注解 AOP 命中验证

写 1 个 IT 起 Web 上下文(`@SpringBootTest(webEnvironment=RANDOM_PORT)`),用
`MockRestServiceServer` 让真实 `RestClient` 走 stub,验:

- `@CircuitBreaker(name="test", fallbackMethod="fallback")` 注解 catch RestClientException
- 50% 5xx 后状态机切 OPEN
- fallback 方法被调用

### Phase 2-B:升级 `DownstreamFallback` 内部实现

把 `callOrThrow` / `callOrFallback` 内部 `try { primary.get() } catch (RestClientException)` 替换为:

```java
return CircuitBreaker.decorateSupplier(
    circuitBreakerRegistry.circuitBreaker(service),
    primary).get();
```

**保留**:
- 现有 `callOrFallback` / `callOrThrow` 方法签名(零调用方改动)
- `console.downstream.fallback` counter(运维告警规则不破)

**新增**:R4J 自带的 `resilience4j.circuitbreaker.*` metrics,与上面 counter 并存。

### Phase 2-C:application.yml 配 instance per service

每个 `service` tag(`trigger` / `orchestrator`)对应一个 CB instance:

```yaml
resilience4j:
  circuitbreaker:
    instances:
      trigger:
        failure-rate-threshold: 50
        sliding-window-size: 10
        minimum-number-of-calls: 5
        wait-duration-in-open-state: 30s
      orchestrator:
        failure-rate-threshold: 50
        sliding-window-size: 10
        minimum-number-of-calls: 5
        wait-duration-in-open-state: 30s
```

### Phase 2-D:Grafana 大盘 + Alertmanager

R4J 自带 `resilience4j.circuitbreaker.state` gauge,用它做"CB OPEN ≥ 1m → page"告警,
不再依赖 `DownstreamFallback` 自己的 counter。

## 不做的

- R4J 3.x:截止 2026-05 还没正式 release,2.3.0 已满足需求
- TimeLimiter:本项目 RestClient 已有 5s/30s 超时,引 TimeLimiter 重复
- Bulkhead:console-api 并发量低,Bulkhead 收益不明确,暂不上
- RateLimiter:console 是内部管理面,无 rate limit 需求
