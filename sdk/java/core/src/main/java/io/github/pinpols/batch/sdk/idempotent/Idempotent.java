package io.github.pinpols.batch.sdk.idempotent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A.3 — 声明式幂等。标在 {@link io.github.pinpols.batch.sdk.task.SdkTaskHandler} 实现类上,声明「同一业务键只执行一次」。
 *
 * <p><b>边界(红线)</b>:幂等是租户「自家业务表」侧的去重,SDK **不写**平台 {@code job_instance} / {@code outbox_event}
 * 等状态表(orchestrator 是唯一状态主机)。去重存储由接入方注入 {@link SdkIdempotencyStore} SPI 实现, SDK core 不硬编码 JDBC /
 * Spring。
 *
 * <p><b>织入方式</b>:不用 Spring AOP(SDK core 禁 Spring),改用显式装饰器 {@link SdkIdempotentHandler#wrap}:命中 key
 * → 跳过 {@code execute} 返已有结果;未命中 → 执行并记录。
 *
 * <p><b>key 表达式</b>(SpEL-free):字面量 + {@code {field}} 占位符,占位符按以下顺序解析——
 *
 * <ol>
 *   <li>{@code {tenantId}} / {@code {jobCode}} / {@code {taskInstanceId}} → 上下文字段;
 *   <li>其余 {@code {x}} → {@code ctx.parameters().get("x")}。
 * </ol>
 *
 * 例:{@code @Idempotent(key = "import:{tenantId}:{bizDate}")}。占位符解析不到值 → 视为业务配置错,执行直接 fail。
 *
 * @see SdkIdempotencyStore
 * @see SdkIdempotentHandler
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Idempotent {

  /** 幂等键表达式(字面量 + {@code {field}} 占位符)。必填。 */
  String key();

  /**
   * 幂等记录存活毫秒数;{@code <= 0} 表示永久(由 store 实现决定语义)。默认 0(永久)。
   *
   * <p>store 自行决定如何用 ttl(过期清理 / 写入带过期列);SDK core 仅透传。
   */
  long ttlMillis() default 0L;
}
