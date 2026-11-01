package io.github.pinpols.batch.console.domain.audit.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.audit.service.ConsoleAiAuthorizationService;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AI 活动流注册：请求冲突与取消归属")
class ConsoleAiStreamRegistryTest {
  @Test
  @DisplayName("未授权用户不能通过应用入口注册或取消活动流")
  void shouldEnforceAuthorizationAtApplicationEntry() {
    ConsoleAiAuthorizationService authorization = mock(ConsoleAiAuthorizationService.class);
    doThrow(BizException.of(ResultCode.FORBIDDEN, ResultCode.FORBIDDEN.detailKey()))
        .when(authorization)
        .assertAllowed();
    ConsoleAiStreamRegistry registry =
        new ConsoleAiStreamRegistry(authorization, mock(ConsoleRequestMetadataResolver.class));
    assertThatThrownBy(() -> registry.register("r", () -> {})).isInstanceOf(BizException.class);
    assertThatThrownBy(() -> registry.cancel("r")).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("活动请求标识重复时拒绝，不同租户或用户不能取消")
  void shouldEnforceConflictAndOwnership() {
    ConsoleRequestMetadataResolver resolver = mock(ConsoleRequestMetadataResolver.class);
    when(resolver.current()).thenReturn(identity("tenant", "user"));
    ConsoleAiStreamRegistry registry =
        new ConsoleAiStreamRegistry(mock(ConsoleAiAuthorizationService.class), resolver);
    AtomicBoolean cancelled = new AtomicBoolean();
    registry.register("request", () -> cancelled.set(true));
    assertThatThrownBy(() -> registry.register("request", () -> {}))
        .isInstanceOf(BizException.class)
        .satisfies(exception ->
            assertThat(((BizException) exception).getCode()).isEqualTo(ResultCode.CONFLICT));
    when(resolver.current()).thenReturn(identity("tenant", "other"));
    assertThatThrownBy(() -> registry.cancel("request"))
        .isInstanceOf(BizException.class)
        .satisfies(exception ->
            assertThat(((BizException) exception).getCode()).isEqualTo(ResultCode.NOT_FOUND));
    assertThat(cancelled).isFalse();
    when(resolver.current()).thenReturn(identity("tenant", "user"));
    registry.cancel("request");
    assertThat(cancelled).isTrue();
  }

  @Test
  @DisplayName("旧请求延迟清理不删除相同标识的新请求")
  void shouldNotRemoveReplacementOnLateCleanup() {
    ConsoleRequestMetadataResolver resolver = mock(ConsoleRequestMetadataResolver.class);
    when(resolver.current()).thenReturn(identity("t", "u"));
    ConsoleAiStreamRegistry registry =
        new ConsoleAiStreamRegistry(mock(ConsoleAiAuthorizationService.class), resolver);
    ConsoleAiStreamRegistry.Registration old = registry.register("request", () -> {});
    registry.remove("request", old);
    AtomicBoolean cancelled = new AtomicBoolean();
    registry.register("request", () -> cancelled.set(true));
    registry.remove("request", old);
    registry.cancel("request");
    assertThat(cancelled).isTrue();
  }

  private static ConsoleRequestMetadata identity(String tenant, String user) {
    return new ConsoleRequestMetadata("request", "trace", tenant, user, null, null);
  }
}
