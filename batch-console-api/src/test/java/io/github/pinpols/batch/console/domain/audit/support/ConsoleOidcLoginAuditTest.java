package io.github.pinpols.batch.console.domain.audit.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.domain.audit.mapper.OperationAuditMapper;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("OIDC 登录审计")
class ConsoleOidcLoginAuditTest {

  @Test
  @DisplayName("成功与失败事件只保留平台账号和请求摘要")
  void shouldRecordSanitizedLoginOutcomes() {
    OperationAuditMapper mapper = mock(OperationAuditMapper.class);
    ConsoleRequestMetadataResolver resolver = mock(ConsoleRequestMetadataResolver.class);
    when(resolver.current())
        .thenReturn(new ConsoleRequestMetadata(
            "request-1", "trace-1", "tenant-a", null, null, "127.0.0.1"));
    ConsoleOidcLoginAudit audit = new ConsoleOidcLoginAudit(mapper, resolver);

    audit.succeeded("tenant-a", "local-user");
    audit.failed("tenant-a", "OIDC_AUTHENTICATION_FAILED");

    ArgumentCaptor<OperationAuditEvent> eventCaptor =
        ArgumentCaptor.forClass(OperationAuditEvent.class);
    verify(mapper, org.mockito.Mockito.times(2)).insert(eventCaptor.capture());
    assertThat(eventCaptor.getAllValues())
        .satisfiesExactly(
            success -> {
              assertThat(success.result()).isEqualTo("SUCCESS");
              assertThat(success.operatorId()).isEqualTo("local-user");
              assertThat(success.traceId()).isEqualTo("trace-1");
              assertThat(success.requestId()).isEqualTo("request-1");
              assertThat(success.ipHash()).isNotEqualTo("127.0.0.1");
            },
            failure -> {
              assertThat(failure.result()).isEqualTo("FAILED");
              assertThat(failure.operatorId()).isEqualTo("anonymous");
              assertThat(failure.errorCode()).isEqualTo("OIDC_AUTHENTICATION_FAILED");
              assertThat(failure.errorMessage()).doesNotContain("subject", "claim", "token");
            });
  }

  @Test
  @DisplayName("审计存储故障不能阻断登录结果处理")
  void shouldContainAuditStorageFailure() {
    OperationAuditMapper mapper = mock(OperationAuditMapper.class);
    ConsoleRequestMetadataResolver resolver = mock(ConsoleRequestMetadataResolver.class);
    when(resolver.current())
        .thenReturn(new ConsoleRequestMetadata(
            "request-1", "trace-1", "tenant-a", null, null, "127.0.0.1"));
    doThrow(new IllegalStateException("database unavailable"))
        .when(mapper)
        .insert(org.mockito.ArgumentMatchers.any(OperationAuditEvent.class));
    ConsoleOidcLoginAudit audit = new ConsoleOidcLoginAudit(mapper, resolver);

    audit.failed("tenant-a", "OIDC_AUTHENTICATION_FAILED");

    verify(mapper).insert(org.mockito.ArgumentMatchers.any(OperationAuditEvent.class));
  }
}
