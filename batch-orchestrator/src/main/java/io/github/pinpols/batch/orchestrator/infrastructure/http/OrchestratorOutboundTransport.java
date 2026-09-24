package io.github.pinpols.batch.orchestrator.infrastructure.http;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 标识 Orchestrator 域使用的出站 HTTP 传输。
 *
 * <p>组合测试与未来模块内聚部署可能同时存在多个传输实现；限定注入确保治理、血缘和传感器始终使用 Orchestrator 策略。
 */
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Qualifier
public @interface OrchestratorOutboundTransport {}
