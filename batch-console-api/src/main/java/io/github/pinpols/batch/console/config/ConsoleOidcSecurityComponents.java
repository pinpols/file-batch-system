package io.github.pinpols.batch.console.config;

import io.github.pinpols.batch.console.infrastructure.rbac.ConsoleOidcAuthenticationFailureHandler;
import io.github.pinpols.batch.console.infrastructure.rbac.ConsoleOidcAuthenticationSuccessHandler;
import io.github.pinpols.batch.console.infrastructure.rbac.DiscardingOidcAuthorizedClientRepository;
import io.github.pinpols.batch.console.infrastructure.rbac.RedisOidcAuthorizationRequestRepository;
import io.github.pinpols.batch.console.support.ratelimit.ConsoleOidcAuthorizationRateLimitFilter;

/** OIDC 安全链协作组件聚合体，避免继续扩大主 SecurityFilterChain 工厂参数列表。 */
public record ConsoleOidcSecurityComponents(
    RedisOidcAuthorizationRequestRepository authorizationRequestRepository,
    ConsoleOidcAuthenticationSuccessHandler successHandler,
    ConsoleOidcAuthenticationFailureHandler failureHandler,
    DiscardingOidcAuthorizedClientRepository authorizedClientRepository,
    ConsoleOidcAuthorizationRateLimitFilter authorizationRateLimitFilter) {}
