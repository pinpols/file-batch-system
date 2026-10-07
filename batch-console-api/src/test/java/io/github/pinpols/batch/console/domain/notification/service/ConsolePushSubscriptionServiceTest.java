package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.config.ConsolePushProperties;
import io.github.pinpols.batch.console.domain.notification.application.contract.request.ConsolePushSubscribeRequest;
import io.github.pinpols.batch.console.domain.notification.entity.ConsolePushSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsolePushSubscriptionMapper;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayName("推送订阅安全边界:参数校验拒绝与真实租户守卫")
class ConsolePushSubscriptionServiceTest {
  private final ConsolePushSubscriptionMapper repository =
      mock(ConsolePushSubscriptionMapper.class);
  private ConsolePushSubscriptionService service;

  @BeforeEach
  void setUp() {
    var properties = new ConsolePushProperties();
    properties.setEnabled(true);
    properties.setPublicKey("public-key");
    properties.setPrivateKey("private-key");
    var metadata = mock(ConsoleRequestMetadataResolver.class);
    when(metadata.current()).thenThrow(new IllegalStateException("no request scope"));
    service = new ConsolePushSubscriptionService(
        repository, new ConsoleTenantGuard(metadata), properties);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
            new ConsolePrincipal("alice", "tenant-a", Set.of("ROLE_TENANT_USER")), "ignored"));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("非法请求与缺失密钥均抛参数错误,不写入订阅")
  void shouldRejectInvalidPayload_whenSubscriptionFieldsAreMissing() {
    var validKeys = new ConsolePushSubscribeRequest.Keys("p256", "auth");
    assertInvalid(null);
    assertInvalid(new ConsolePushSubscribeRequest(" ", null, validKeys));
    assertInvalid(new ConsolePushSubscribeRequest("https://push.invalid", null, null));
    assertInvalid(new ConsolePushSubscribeRequest(
        "https://push.invalid", null, new ConsolePushSubscribeRequest.Keys(null, "auth")));
    assertInvalid(new ConsolePushSubscribeRequest(
        "https://push.invalid", null, new ConsolePushSubscribeRequest.Keys("p256", null)));
    verifyNoInteractions(repository);
  }

  @Test
  @DisplayName("合法请求不能绕过租户守卫写入其他租户")
  void shouldRejectCrossTenant_whenPayloadIsValid() {
    assertThatThrownBy(() -> service.subscribe("tenant-b", "alice", validRequest(), "browser"))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(ResultCode.FORBIDDEN);
    verifyNoInteractions(repository);
  }

  @Test
  @DisplayName("合法同租户请求仍成功写入订阅,字段保持原样")
  void shouldPersistSubscription_whenTenantMatches() {
    service.subscribe("tenant-a", "alice", validRequest(), "browser");
    var captured = ArgumentCaptor.forClass(ConsolePushSubscriptionEntity.class);
    verify(repository).upsert(captured.capture());
    org.assertj.core.api.Assertions.assertThat(captured.getValue().getTenantId())
        .isEqualTo("tenant-a");
    org.assertj.core.api.Assertions.assertThat(captured.getValue().getAuthSecret())
        .isEqualTo("auth");
  }

  private void assertInvalid(ConsolePushSubscribeRequest request) {
    assertThatThrownBy(() -> service.subscribe("tenant-a", "alice", request, "browser"))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(ResultCode.INVALID_ARGUMENT);
  }

  private static ConsolePushSubscribeRequest validRequest() {
    return new ConsolePushSubscribeRequest(
        "https://push.invalid/subscription",
        null,
        new ConsolePushSubscribeRequest.Keys("p256", "auth"));
  }
}
