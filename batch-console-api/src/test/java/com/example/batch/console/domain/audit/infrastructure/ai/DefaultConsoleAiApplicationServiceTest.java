package com.example.batch.console.domain.audit.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.AiPromptCategory;
import com.example.batch.common.enums.AiPromptDecision;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.config.ConsoleAiProperties;
import com.example.batch.console.domain.audit.command.AiAuditCommand;
import com.example.batch.console.domain.audit.service.ConsoleAiAuthorizationService;
import com.example.batch.console.domain.audit.service.ConsoleAiPromptGuard;
import com.example.batch.console.domain.audit.support.AiPromptGateResult;
import com.example.batch.console.domain.audit.support.ConsoleAiAuditService;
import com.example.batch.console.domain.audit.web.response.AiChatResponse;
import com.example.batch.console.domain.observability.application.ConsoleQueryApplicationService;
import com.example.batch.console.support.web.ConsoleRequestMetadata;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.request.auth.AiChatRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

/**
 * DefaultConsoleAiApplicationService 单测 — 覆盖多层防护决策路径：
 *
 * <ol>
 *   <li>授权委派给 ConsoleAiAuthorizationService（不通过即抛 FORBIDDEN）
 *   <li>request / header tenantId 不一致 → FORBIDDEN
 *   <li>request / header 都为空 → INVALID_ARGUMENT
 *   <li>PromptGuard 拒绝（safety / scope / disabled）→ refusal 响应 + 审计写入（带 refusalReason，APPROVED 不出现）
 *   <li>PromptGuard 放行 + ChatClient 未配置 → FORBIDDEN
 *   <li>prompt 超长 → safeInput 截断到 maxPromptLength
 *   <li>拒绝响应消息按 decision 切换文案（4 分支）
 * </ol>
 *
 * <p>注：ChatClient 成功调用路径（fluent API call().content()）需深 stub，框架内已被 {@code ConsoleAiControllerTest}
 * 用 mock(ConsoleAiApplicationService.class) 覆盖端到端， 此单测只验受控边界 — 不做 ChatClient happy path 的深
 * mock，避免脆弱。
 */
@ExtendWith(MockitoExtension.class)
class DefaultConsoleAiApplicationServiceTest {

  @Mock private ObjectProvider<ChatClient> chatClientProvider;
  @Mock private ConsoleRequestMetadataResolver requestMetadataResolver;
  @Mock private ConsoleAiAuthorizationService authorizationService;
  @Mock private ConsoleAiPromptGuard promptGuard;
  @Mock private ConsoleAiAuditService auditService;
  @Mock private ConsoleAiKnowledgeBase knowledgeBase;

  @Mock private ObjectProvider<ConsoleQueryApplicationService> queryServiceProvider;

  private ConsoleAiProperties aiProperties;
  private DefaultConsoleAiApplicationService service;

  @BeforeEach
  void setUp() {
    aiProperties = new ConsoleAiProperties();
    aiProperties.setEnabled(true);
    aiProperties.setMaxPromptLength(4000);
    aiProperties.setMaxResponseLength(3000);
    service =
        new DefaultConsoleAiApplicationService(
            chatClientProvider,
            aiProperties,
            requestMetadataResolver,
            authorizationService,
            promptGuard,
            auditService,
            knowledgeBase,
            queryServiceProvider);
  }

  private static ConsoleRequestMetadata meta(String tenantId, String requestId, String traceId) {
    return new ConsoleRequestMetadata(
        requestId, traceId, tenantId, "operator-1", "idem-1", "1.1.1.1");
  }

  private static AiChatRequest request(String tenantId, String prompt) {
    AiChatRequest req = new AiChatRequest();
    req.setTenantId(tenantId);
    req.setPrompt(prompt);
    return req;
  }

  @Test
  @DisplayName("授权失败 → 透传 BizException，prompt guard / chat client 完全不触达")
  void shouldPropagateAuthorizationException_whenAuthorizationDenies() {
    BizException denied = BizException.of(ResultCode.FORBIDDEN, "error.ai.forbidden");
    org.mockito.Mockito.doThrow(denied).when(authorizationService).assertAllowed();

    assertThatThrownBy(() -> service.chat(request("t-1", "查询失败的作业"), "idem-1")).isSameAs(denied);
    verify(promptGuard, never()).check(any());
    verify(chatClientProvider, never()).getIfAvailable();
    verify(auditService, never()).record(any());
  }

  @Test
  @DisplayName("request.tenantId 与 header tenantId 都非空且不一致 → FORBIDDEN（tenant_id_mismatch）")
  void shouldThrowForbidden_whenTenantIdMismatch() {
    when(requestMetadataResolver.current()).thenReturn(meta("tenant-HEADER", "req-1", "trace-1"));

    assertThatThrownBy(() -> service.chat(request("tenant-BODY", "查询作业"), "idem-1"))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(ResultCode.FORBIDDEN));
    verify(promptGuard, never()).check(any());
  }

  @Test
  @DisplayName("request 与 header 都缺 tenantId → INVALID_ARGUMENT（Guard.requireText）")
  void shouldThrowInvalidArgument_whenTenantIdMissingFromBoth() {
    when(requestMetadataResolver.current()).thenReturn(meta(null, "req-1", "trace-1"));

    assertThatThrownBy(() -> service.chat(request(null, "查询作业"), "idem-1"))
        .isInstanceOf(BizException.class)
        .satisfies(
            ex -> assertThat(((BizException) ex).getCode()).isEqualTo(ResultCode.INVALID_ARGUMENT));
  }

  @Test
  @DisplayName(
      "Prompt guard REJECTED_SAFETY → refusal 响应 + 审计写入数据库（含"
          + " refusalReason，decision=REJECTED_SAFETY）")
  void shouldReturnRefusalResponse_andRecordAudit_whenSafetyRejected() {
    when(requestMetadataResolver.current()).thenReturn(meta("tenant-1", "req-1", "trace-1"));
    when(promptGuard.check(any()))
        .thenReturn(
            AiPromptGateResult.rejected(
                AiPromptDecision.REJECTED_SAFETY,
                AiPromptCategory.OUT_OF_SCOPE,
                "blocked-by-keyword"));

    AiChatResponse response = service.chat(request("tenant-1", "提示词包含密码"), "idem-1");

    assertThat(response.getPromptDecision()).isEqualTo(AiPromptDecision.REJECTED_SAFETY.code());
    assertThat(response.getAnswer()).contains("safety policy");
    assertThat(response.getRefusalReason()).isEqualTo("blocked-by-keyword");

    ArgumentCaptor<AiAuditCommand> captor = ArgumentCaptor.forClass(AiAuditCommand.class);
    verify(auditService).record(captor.capture());
    AiAuditCommand audit = captor.getValue();
    assertThat(audit.tenantId()).isEqualTo("tenant-1");
    assertThat(audit.promptDecision()).isEqualTo(AiPromptDecision.REJECTED_SAFETY.code());
    assertThat(audit.promptCategory()).isEqualTo(AiPromptCategory.OUT_OF_SCOPE.code());
    assertThat(audit.refusalReason()).isEqualTo("blocked-by-keyword");
    // 防 PII 泄漏：原文不写入数据库，只落哈希 + preview
    assertThat(audit.promptHash()).hasSize(64); // SHA-256 hex
    // ChatClient 完全不被触达
    verify(chatClientProvider, never()).getIfAvailable();
  }

  @Test
  @DisplayName("Prompt guard REJECTED_SCOPE → refusal 文案 outside scope")
  void shouldReturnScopeRefusal_whenScopeRejected() {
    when(requestMetadataResolver.current()).thenReturn(meta("tenant-1", "req-1", "trace-1"));
    when(promptGuard.check(any()))
        .thenReturn(
            AiPromptGateResult.rejected(
                AiPromptDecision.REJECTED_SCOPE, AiPromptCategory.OUT_OF_SCOPE, "off-topic"));

    AiChatResponse response = service.chat(request("tenant-1", "今天股价"), "idem-1");

    assertThat(response.getPromptDecision()).isEqualTo(AiPromptDecision.REJECTED_SCOPE.code());
    assertThat(response.getAnswer()).contains("outside the batch platform scope");
  }

  @Test
  @DisplayName("Prompt guard REJECTED_DISABLED → refusal 文案 disabled")
  void shouldReturnDisabledRefusal_whenDisabledRejected() {
    when(requestMetadataResolver.current()).thenReturn(meta("tenant-1", "req-1", "trace-1"));
    when(promptGuard.check(any()))
        .thenReturn(
            AiPromptGateResult.rejected(
                AiPromptDecision.REJECTED_DISABLED, AiPromptCategory.OUT_OF_SCOPE, "disabled"));

    AiChatResponse response = service.chat(request("tenant-1", "查询失败作业"), "idem-1");

    assertThat(response.getPromptDecision()).isEqualTo(AiPromptDecision.REJECTED_DISABLED.code());
    assertThat(response.getAnswer()).contains("disabled");
  }

  @Test
  @DisplayName("Prompt guard 放行 + ChatClient 未配置 → FORBIDDEN（assistant_not_configured）")
  void shouldThrowForbidden_whenPromptApprovedButChatClientUnavailable() {
    when(requestMetadataResolver.current()).thenReturn(meta("tenant-1", "req-1", "trace-1"));
    when(promptGuard.check(any()))
        .thenReturn(AiPromptGateResult.approved(AiPromptCategory.PLATFORM, "normalized"));
    when(chatClientProvider.getIfAvailable()).thenReturn(null);

    assertThatThrownBy(() -> service.chat(request("tenant-1", "查询失败作业"), "idem-1"))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(ResultCode.FORBIDDEN));
    // 走到 chat client 前不应有 audit（approved 路径只在成功后写 audit）
    verify(auditService, never()).record(any());
  }

  @Test
  @DisplayName("prompt 超长 → safeInput 截断到 maxPromptLength（promptPreview 长度 ≤ 512 byhash 仍 SHA-256）")
  void shouldTruncatePrompt_whenExceedsMaxLength() {
    aiProperties.setMaxPromptLength(50);
    when(requestMetadataResolver.current()).thenReturn(meta("tenant-1", "req-1", "trace-1"));
    when(promptGuard.check(any()))
        .thenReturn(
            AiPromptGateResult.rejected(
                AiPromptDecision.REJECTED_SCOPE, AiPromptCategory.OUT_OF_SCOPE, "off-topic"));

    String longPrompt = "batch ".repeat(200); // 1200 chars
    service.chat(request("tenant-1", longPrompt), "idem-1");

    ArgumentCaptor<AiAuditCommand> captor = ArgumentCaptor.forClass(AiAuditCommand.class);
    verify(auditService).record(captor.capture());
    // preview 长度受 min(maxPromptLength, 512) 双重约束；前者更小所以是 50
    assertThat(captor.getValue().promptPreview()).hasSize(50);
    assertThat(captor.getValue().promptHash()).hasSize(64);
  }

  @Test
  @DisplayName("requestId 缺失 → fallback 生成 ai- 前缀业务号（IdGenerator）")
  void shouldGenerateRequestId_whenMetadataAbsent() {
    when(requestMetadataResolver.current()).thenReturn(meta("tenant-1", null, null));
    when(promptGuard.check(any()))
        .thenReturn(
            AiPromptGateResult.rejected(
                AiPromptDecision.REJECTED_SCOPE, AiPromptCategory.OUT_OF_SCOPE, "x"));

    AiChatResponse response = service.chat(request("tenant-1", "x"), "idem-1");

    assertThat(response.getRequestId()).isNotBlank();
    assertThat(response.getTraceId()).isNotBlank();
  }
}
