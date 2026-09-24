package io.github.pinpols.batch.console.support.http;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 标识 Console 域使用的出站 HTTP 传输。
 *
 * <p>E2E 组合应用会同时装配 Console 与 Orchestrator；显式限定可避免两个模块的传输策略按类型注入时互相覆盖。
 */
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Qualifier
public @interface ConsoleOutboundTransport {}
