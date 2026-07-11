package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.AiPromptCategory;
import io.github.pinpols.batch.common.enums.AiPromptDecision;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.config.ConsoleAiProperties;
import io.github.pinpols.batch.console.domain.audit.command.AiAuditCommand;
import io.github.pinpols.batch.console.domain.audit.service.ConsoleAiAuthorizationService;
import io.github.pinpols.batch.console.domain.audit.service.ConsoleAiPromptGuard;
import io.github.pinpols.batch.console.domain.audit.support.AiPromptGateResult;
import io.github.pinpols.batch.console.domain.audit.support.ConsoleAiAuditService;
import io.github.pinpols.batch.console.domain.audit.web.response.AiChatResponse;
import io.github.pinpols.batch.console.domain.observability.application.ConsoleQueryApplicationService;
import io.github.pinpols.batch.console.domain.ops.service.ConsoleClusterDiagnosticService;
import io.github.pinpols.batch.console.support.ratelimit.SlidingWindowRateLimiter;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import io.github.pinpols.batch.console.web.request.auth.AiChatRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.QueryTimeoutException;

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
  @Mock private ObjectProvider<ConsoleClusterDiagnosticService> diagnosticServiceProvider;
  @Mock private SlidingWindowRateLimiter rateLimiter;

  private ConsoleAiProperties aiProperties;
  private SimpleMeterRegistry meterRegistry;
  private ConsoleAiMetrics aiMetrics;
  private DefaultConsoleAiApplicationService service;

  @BeforeEach
  void setUp() {
    aiProperties = new ConsoleAiProperties();
    aiProperties.setEnabled(true);
    aiProperties.setMaxPromptLength(4000);
    aiProperties.setMaxResponseLength(3000);
    // 默认关闭限流,让既有决策路径用例不触达 rateLimiter(strict stubbing);限流由专门用例开启。
    aiProperties.setRateLimitPerMinute(0);
    aiProperties.setRequestTimeout(Duration.ofSeconds(5));
    meterRegistry = new SimpleMeterRegistry();
    aiMetrics = new ConsoleAiMetrics(meterRegistry);
    service =
        new DefaultConsoleAiApplicationService(
            chatClientProvider,
            aiProperties,
            requestMetadataResolver,
            authorizationService,
            promptGuard,
            auditService,
            knowledgeBase,
            queryServiceProvider,
            diagnosticServiceProvider,
            rateLimiter,
            aiMetrics);
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

  // ── ① 成本计量 ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("模型成功应答 → token 用量打指标 + 落审计;requests{decision=approved} 自增")
  void shouldRecordTokenMetricsAndAudit_whenModelSucceeds() {
    stubApprovedChatClient(chatResponseWithUsage("按需重试即可。", 120, 45));

    AiChatResponse response = service.chat(request("tenant-1", "查询失败作业"), "idem-1");

    assertThat(response.getPromptDecision()).isEqualTo(AiPromptDecision.APPROVED.code());
    assertThat(response.getAnswer()).contains("按需重试即可。");

    assertThat(tokenCount("prompt")).isEqualTo(120.0);
    assertThat(tokenCount("completion")).isEqualTo(45.0);
    assertThat(decisionCount("approved")).isEqualTo(1.0);

    ArgumentCaptor<AiAuditCommand> captor = ArgumentCaptor.forClass(AiAuditCommand.class);
    verify(auditService).record(captor.capture());
    assertThat(captor.getValue().promptTokens()).isEqualTo(120);
    assertThat(captor.getValue().completionTokens()).isEqualTo(45);
    assertThat(captor.getValue().promptDecision()).isEqualTo(AiPromptDecision.APPROVED.code());
  }

  @Test
  @DisplayName("成功但 metadata 无 usage(EmptyUsage 归 0)→ 不打 token 指标,仍正常返回")
  void shouldTolerateMissingUsage_whenModelSucceedsWithoutMetadata() {
    ChatResponse noUsage = new ChatResponse(List.of(new Generation(new AssistantMessage("答复"))));
    stubApprovedChatClient(noUsage);

    AiChatResponse response = service.chat(request("tenant-1", "查询失败作业"), "idem-1");

    assertThat(response.getPromptDecision()).isEqualTo(AiPromptDecision.APPROVED.code());
    // EmptyUsage 的 token 计数为 0,不触发指标自增(仅 >0 才 increment)
    assertThat(tokenCount("prompt")).isEqualTo(0.0);
    ArgumentCaptor<AiAuditCommand> captor = ArgumentCaptor.forClass(AiAuditCommand.class);
    verify(auditService).record(captor.capture());
    assertThat(captor.getValue().promptTokens()).isEqualTo(0);
  }

  // ── ② 调用限流 ────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "AI 限流超限 → 429 RATE_LIMITED;key 含 tenant + user;门禁/模型不触达,requests{decision=rate_limited}")
  void shouldReturn429_whenRateLimitExceeded() {
    aiProperties.setRateLimitPerMinute(20);
    when(requestMetadataResolver.current()).thenReturn(meta("tenant-1", "req-1", "trace-1"));
    ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
    when(rateLimiter.tryAcquire(keyCaptor.capture(), eq(20))).thenReturn(false);

    assertThatThrownBy(() -> service.chat(request("tenant-1", "查询失败作业"), "idem-1"))
        .isInstanceOf(BizException.class)
        .satisfies(
            ex -> assertThat(((BizException) ex).getCode()).isEqualTo(ResultCode.RATE_LIMITED));

    // #779:限流 key 必须含 tenant,防跨租户互相压制;同时含 user 做 per-user 限流
    assertThat(keyCaptor.getValue()).contains("tenant:tenant-1").contains("user:operator-1");
    assertThat(decisionCount("rate_limited")).isEqualTo(1.0);
    verify(promptGuard, never()).check(any());
    verify(chatClientProvider, never()).getIfAvailable();
    verify(auditService, never()).record(any());
  }

  @Test
  @DisplayName("不同租户限流 key 不同 → 跨租户不互相压制")
  void shouldUseTenantScopedKey_soTenantsDoNotSuppressEachOther() {
    aiProperties.setRateLimitPerMinute(20);
    when(requestMetadataResolver.current()).thenReturn(meta("tenant-A", "req-1", "trace-1"));
    ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
    when(rateLimiter.tryAcquire(keyCaptor.capture(), anyInt())).thenReturn(false);

    assertThatThrownBy(() -> service.chat(request("tenant-A", "查询失败作业"), "idem-1"))
        .isInstanceOf(BizException.class);

    assertThat(keyCaptor.getValue()).contains("tenant-A").doesNotContain("tenant-B");
  }

  @Test
  @DisplayName("限流器 Redis 不可达 → fail-open,不 429,继续走门禁(与限流过滤器一致)")
  void shouldFailOpen_whenRateLimiterRedisUnavailable() {
    aiProperties.setRateLimitPerMinute(20);
    when(requestMetadataResolver.current()).thenReturn(meta("tenant-1", "req-1", "trace-1"));
    when(rateLimiter.tryAcquire(anyString(), anyInt()))
        .thenThrow(new QueryTimeoutException("redis down"));
    when(promptGuard.check(any()))
        .thenReturn(
            AiPromptGateResult.rejected(
                AiPromptDecision.REJECTED_SCOPE, AiPromptCategory.OUT_OF_SCOPE, "off-topic"));

    AiChatResponse response = service.chat(request("tenant-1", "今天股价"), "idem-1");

    // 未被 429 拦截,正常走到门禁(此处被 scope 拒绝)
    assertThat(response.getPromptDecision()).isEqualTo(AiPromptDecision.REJECTED_SCOPE.code());
  }

  // ── ③ 优雅降级 ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("模型调用抛异常 → 优雅降级(FAILED,非 500)+ 审计记失败;requests{decision=failed}")
  void shouldDegradeGracefully_whenModelCallThrows() {
    ChatClient.CallResponseSpec callSpec = stubApprovedChatClientChain();
    when(callSpec.chatResponse()).thenThrow(new RuntimeException("provider 503"));

    AiChatResponse response = service.chat(request("tenant-1", "查询失败作业"), "idem-1");

    // 关键:不裸抛 500,返回降级响应
    assertThat(response.getPromptDecision()).isEqualTo(AiPromptDecision.FAILED.code());
    assertThat(response.getAnswer()).contains("暂时不可用");
    assertThat(decisionCount("failed")).isEqualTo(1.0);

    ArgumentCaptor<AiAuditCommand> captor = ArgumentCaptor.forClass(AiAuditCommand.class);
    verify(auditService).record(captor.capture());
    assertThat(captor.getValue().promptDecision()).isEqualTo(AiPromptDecision.FAILED.code());
    assertThat(captor.getValue().refusalReason()).startsWith("model_call_failed");
  }

  @Test
  @DisplayName("模型调用超时(超 requestTimeout)→ 优雅降级 FAILED,不无限等")
  void shouldDegradeGracefully_whenModelCallTimesOut() {
    aiProperties.setRequestTimeout(Duration.ofMillis(150));
    ChatClient.CallResponseSpec callSpec = stubApprovedChatClientChain();
    when(callSpec.chatResponse())
        .thenAnswer(
            invocation -> {
              Thread.sleep(2000);
              return chatResponseWithUsage("迟到的答复", 1, 1);
            });

    long start = System.currentTimeMillis();
    AiChatResponse response = service.chat(request("tenant-1", "查询失败作业"), "idem-1");
    long elapsed = System.currentTimeMillis() - start;

    assertThat(response.getPromptDecision()).isEqualTo(AiPromptDecision.FAILED.code());
    assertThat(elapsed).isLessThan(1500); // 被 150ms 硬超时兜住,没等满 2s
    assertThat(decisionCount("failed")).isEqualTo(1.0);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static ChatResponse chatResponseWithUsage(String text, int prompt, int completion) {
    return new ChatResponse(
        List.of(new Generation(new AssistantMessage(text))),
        ChatResponseMetadata.builder().usage(new DefaultUsage(prompt, completion)).build());
  }

  /** stub 授权/门禁放行 + RAG 空 + 工具关 + ChatClient fluent 链,返回可继续 stub 的 CallResponseSpec。 */
  private ChatClient.CallResponseSpec stubApprovedChatClientChain() {
    aiProperties.getTools().setEnabled(false);
    when(requestMetadataResolver.current()).thenReturn(meta("tenant-1", "req-1", "trace-1"));
    when(promptGuard.check(any()))
        .thenReturn(AiPromptGateResult.approved(AiPromptCategory.PLATFORM, "normalized"));
    when(knowledgeBase.retrieve(any())).thenReturn(List.of());

    ChatClient chatClient = mock(ChatClient.class);
    ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
    ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
    when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.system(anyString())).thenReturn(requestSpec);
    when(requestSpec.user(anyString())).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(callSpec);
    return callSpec;
  }

  private void stubApprovedChatClient(ChatResponse chatResponse) {
    ChatClient.CallResponseSpec callSpec = stubApprovedChatClientChain();
    when(callSpec.chatResponse()).thenReturn(chatResponse);
  }

  private double tokenCount(String type) {
    io.micrometer.core.instrument.Counter counter =
        meterRegistry.find("batch.console.ai.tokens.total").tag("type", type).counter();
    return counter == null ? 0.0 : counter.count();
  }

  private double decisionCount(String decision) {
    io.micrometer.core.instrument.Counter counter =
        meterRegistry.find("batch.console.ai.requests.total").tag("decision", decision).counter();
    return counter == null ? 0.0 : counter.count();
  }
}
