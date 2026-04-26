package com.example.batch.console.support.cache;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明本方法"成功完成后应触发某种 console 配置缓存失效"。
 *
 * <p><b>R-4.8 · 缓存失效 AOP 化</b>： 旧模式是业务代码手动调 {@code
 * ConsoleConfigCacheInvalidationService.evictXxx(...)}， 写路径漏调就静默留下陈旧缓存。本注解 + {@link
 * ConsoleCacheInvalidationAspect} 把失效 逻辑收到 AOP 切面：方法返回后自动调对应 evict，降低漏掉概率。
 *
 * <p><b>使用示例</b>：
 *
 * <pre>{@code
 * @InvalidatesConsoleCache(target = Target.JOB_DEFINITION, tenantSpel = "#cmd.tenantId()",
 *     codeSpel = "#cmd.jobCode()")
 * public void updateJobDefinition(UpdateJobDefinitionCommand cmd) { ... }
 * }</pre>
 *
 * <p>{@code tenantSpel} / {@code codeSpel} 支持 SpEL 表达式引用方法入参（按 index 或名字）； 不填则表示 target
 * 语义上是"全租户清"（如 {@link Target#ALL_JOB_DEFINITIONS_BY_TENANT}， 只需要 tenantSpel）。
 *
 * <p>配合守护测试 {@code ConsoleCacheInvalidationCoverageTest} 扫描 batch-console-api 的 write-path service
 * 方法，确认凡是动 job_definition / workflow_definition / business_calendar / batch_window /
 * file_channel_config / tenant_quota_policy 等受缓存表的方法都加了本注解 （或手动调 evict）。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface InvalidatesConsoleCache {

  Target target();

  /** SpEL 表达式提取 tenantId；例如 {@code "#cmd.tenantId()"} 或 {@code "#tenantId"}。 */
  String tenantSpel() default "";

  /** SpEL 表达式提取 code；例如 {@code "#cmd.jobCode()"}。按 target 语义可选。 */
  String codeSpel() default "";

  /**
   * 缓存目标分类；对应 {@link
   * com.example.batch.console.infrastructure.ConsoleConfigCacheInvalidationService} 的 evictXxx
   * 方法族。新增目标前先在 Invalidation Service 补齐对应 evict 方法。
   */
  enum Target {
    JOB_DEFINITION, // evictJobDefinition(tenantId, code)
    ALL_JOB_DEFINITIONS_BY_TENANT, // evictAllJobDefinitions(tenantId)
    WORKFLOW_DEFINITION, // evictWorkflowDefinition(tenantId, code)
    BUSINESS_CALENDAR, // evictBusinessCalendar(tenantId, code)
    BATCH_WINDOW, // evictBatchWindow(tenantId, code)
    TENANT_QUOTA_POLICIES, // evictQuotaPolicies(tenantId)
    META_OPTIONS // evictMetaOptions(tenantId)
  }
}
