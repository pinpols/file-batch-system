package io.github.pinpols.batch.console.domain.audit.web;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.IdGenerator;
import io.github.pinpols.batch.console.application.contract.request.auth.AiChatRequest;
import io.github.pinpols.batch.console.config.ConsoleAiClients;
import io.github.pinpols.batch.console.config.ConsoleAiProperties;
import io.github.pinpols.batch.console.domain.audit.application.ai.ConsoleAiApplicationService;
import io.github.pinpols.batch.console.domain.audit.application.contract.response.AiChatResponse;
import io.github.pinpols.batch.console.domain.audit.infrastructure.ai.ConsoleAiAttachmentService;
import io.github.pinpols.batch.console.domain.audit.service.ConsoleAiAuthorizationService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import io.github.pinpols.batch.console.support.web.Idempotent;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** 控制台 AI 对话 REST（Spring AI）。 */
@RestController
@Validated
@RequestMapping("/api/console/ai")
@RequiredArgsConstructor
@Idempotent
public class ConsoleAiController {

  private final ConsoleAiApplicationService applicationService;
  private final ConsoleResponseFactory responseFactory;
  private final ConsoleAiAuthorizationService authorizationService;
  private final ConsoleRequestMetadataResolver metadataResolver;
  private final ConsoleAiProperties aiProperties;
  private final ConsoleAiAttachmentService attachmentService;
  private final ObjectProvider<ConsoleAiClients> chatClientsProvider;
  private final Map<String, ActiveStream> activeStreams = new ConcurrentHashMap<>();
  private final ExecutorService streamExecutor =
      new ThreadPoolExecutor(0, 16, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
        Thread thread = new Thread(runnable, "console-ai-stream");
        thread.setDaemon(true);
        return thread;
      });

  @PreDestroy
  void shutdownStreamExecutor() {
    streamExecutor.shutdownNow();
  }

  @GetMapping("/capabilities")
  public CommonResponse<AiCapabilities> capabilities() {
    authorizationService.assertAllowed();
    ConsoleAiClients clients = chatClientsProvider.getIfAvailable();
    boolean imageInput = attachmentService.available()
        && clients != null
        && clients.primary() != null
        && clients.primary().imageInput();
    return responseFactory.success(new AiCapabilities(
        imageInput,
        imageInput ? aiProperties.getImage().getMaxImages() : 0,
        imageInput ? aiProperties.getImage().getMaxFileBytes() : 0,
        imageInput ? aiProperties.getImage().getMaxTotalBytes() : 0));
  }

  @PostMapping(value = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public CommonResponse<ConsoleAiAttachmentService.AttachmentView> uploadAttachment(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @RequestParam UUID clientAttachmentId,
      @RequestParam("file") MultipartFile file)
      throws IOException {
    authorizationService.assertAllowed();
    ConsoleAiClients clients = chatClientsProvider.getIfAvailable();
    if (clients == null || clients.primary() == null || !clients.primary().imageInput()) {
      throw BizException.of(ResultCode.FORBIDDEN, "error.ai.assistant_not_configured");
    }
    if (file.isEmpty() || file.getSize() > aiProperties.getImage().getMaxFileBytes()) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.ai.image_invalid");
    }
    ConsoleRequestMetadata metadata = metadataResolver.current();
    return responseFactory.success(attachmentService.upload(
        metadata.tenantId(), metadata.operatorId(), clientAttachmentId, file.getBytes()));
  }

  @GetMapping("/attachments/by-client-id/{clientAttachmentId}")
  public CommonResponse<ConsoleAiAttachmentService.AttachmentView> attachmentStatus(
      @PathVariable UUID clientAttachmentId) {
    authorizationService.assertAllowed();
    ConsoleRequestMetadata metadata = metadataResolver.current();
    return responseFactory.success(
        attachmentService.status(metadata.tenantId(), metadata.operatorId(), clientAttachmentId));
  }

  @GetMapping("/attachments/{id}/content")
  public ResponseEntity<byte[]> attachmentContent(@PathVariable UUID id) {
    authorizationService.assertAllowed();
    ConsoleRequestMetadata metadata = metadataResolver.current();
    ConsoleAiAttachmentService.ImageContent image =
        attachmentService.content(metadata.tenantId(), metadata.operatorId(), id);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .contentType(MediaType.parseMediaType(image.mediaType()))
        .body(image.bytes());
  }

  @DeleteMapping("/attachments/{id}")
  public CommonResponse<Void> deleteAttachment(@PathVariable UUID id) {
    authorizationService.assertAllowed();
    ConsoleRequestMetadata metadata = metadataResolver.current();
    attachmentService.deleteDraft(metadata.tenantId(), metadata.operatorId(), id);
    return responseFactory.success(null);
  }

  public record AiCapabilities(
      boolean imageInput, int maxImages, int maxImageBytes, long maxTotalBytes) {}

  @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter chatStream(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody AiChatRequest request) {
    authorizationService.assertAllowed();
    ConsoleRequestMetadata metadata = metadataResolver.current();
    String requestId = EmptyChecks.isNull(metadata.requestId())
        ? IdGenerator.newBusinessNo("ai")
        : metadata.requestId();
    ConsoleRequestMetadata streamMetadata = new ConsoleRequestMetadata(
        requestId,
        metadata.traceId(),
        metadata.tenantId(),
        metadata.operatorId(),
        idempotencyKey,
        metadata.clientIp());
    Locale requestLocale = LocaleContextHolder.getLocale();
    long timeout = Math.max(1, aiProperties.getRequestTimeout().toMillis()) + 10_000;
    SseEmitter emitter = new SseEmitter(timeout);
    ActiveStream active = new ActiveStream(metadata.tenantId(), metadata.operatorId(), emitter);
    if (EmptyChecks.isNotNull(activeStreams.putIfAbsent(requestId, active))) {
      throw BizException.of(ResultCode.CONFLICT, "error.common.state_conflict");
    }
    emitter.onTimeout(active::cancel);
    emitter.onError(error -> active.cancel());
    emitter.onCompletion(active::cancel);
    try {
      streamExecutor.execute(() -> {
        LocaleContextHolder.setLocale(requestLocale);
        try {
          active.send("started", Map.of("requestId", requestId));
          AiChatResponse result =
              applicationService.chatStream(request, idempotencyKey, streamMetadata, active);
          if (!active.isCancelled()) {
            active.send("completed", result);
          }
        } catch (CancellationException ignored) {
          // 客户端已停止；服务仍须结算并审计本轮请求。
        } catch (Exception exception) {
          if (!active.isCancelled()) {
            try {
              String code = exception instanceof BizException bizException
                  ? bizException.getCode().code()
                  : "STREAM_FAILED";
              active.send("failed", Map.of("code", code));
            } catch (IOException | UncheckedIOException ignored) {
              active.cancel();
            }
          }
        } finally {
          LocaleContextHolder.resetLocaleContext();
          active.finish();
          activeStreams.remove(requestId, active);
          emitter.complete();
        }
      });
    } catch (RejectedExecutionException exception) {
      activeStreams.remove(requestId, active);
      throw BizException.of(ResultCode.SERVICE_UNAVAILABLE, "error.ai.assistant_not_configured");
    }
    return emitter;
  }

  @PostMapping("/chat/stream/{requestId}/cancel")
  public CommonResponse<Void> cancelChatStream(@PathVariable String requestId) {
    authorizationService.assertAllowed();
    ConsoleRequestMetadata metadata = metadataResolver.current();
    ActiveStream active = activeStreams.get(requestId);
    if (EmptyChecks.isNull(active)
        || !Objects.equals(active.tenantId, metadata.tenantId())
        || !Objects.equals(active.operatorId, metadata.operatorId())) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.common.not_found_detail");
    }
    active.cancel();
    return responseFactory.success(null);
  }

  private static final class ActiveStream implements ConsoleAiApplicationService.StreamObserver {
    private final String tenantId;
    private final String operatorId;
    private final SseEmitter emitter;
    private final Sinks.Empty<Void> cancellation = Sinks.empty();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean emitted = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();

    private ActiveStream(String tenantId, String operatorId, SseEmitter emitter) {
      this.tenantId = tenantId;
      this.operatorId = operatorId;
      this.emitter = emitter;
    }

    private void send(String event, Object payload) throws IOException {
      emitter.send(SseEmitter.event().name(event).data(payload));
    }

    @Override
    public void onDelta(String text) {
      if (isCancelled()) throw new CancellationException("AI stream cancelled");
      try {
        send("delta", Map.of("text", text));
        emitted.set(true);
      } catch (IOException exception) {
        cancel();
        throw new UncheckedIOException(exception);
      }
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }

    @Override
    public boolean hasEmitted() {
      return emitted.get();
    }

    @Override
    public Mono<Void> cancellationSignal() {
      return cancellation.asMono();
    }

    private void cancel() {
      if (!finished.get() && cancelled.compareAndSet(false, true)) {
        cancellation.tryEmitEmpty();
      }
    }

    private void finish() {
      finished.set(true);
    }
  }

  @GetMapping("/conversations")
  public CommonResponse<List<ConsoleAiApplicationService.ConversationSummary>> conversations(
      @RequestParam(defaultValue = "20") int limit) {
    authorizationService.assertAllowed();
    ConsoleRequestMetadata metadata = metadataResolver.current();
    return responseFactory.success(
        applicationService.conversations(metadata.tenantId(), metadata.operatorId(), limit));
  }

  @GetMapping("/conversations/page")
  public CommonResponse<PageResponse<ConsoleAiApplicationService.ConversationSummary>>
      conversationPage(
          @RequestParam(required = false) String cursor,
          @RequestParam(defaultValue = "20") int limit) {
    authorizationService.assertAllowed();
    ConsoleRequestMetadata metadata = metadataResolver.current();
    return responseFactory.success(applicationService.conversationPage(
        metadata.tenantId(), metadata.operatorId(), cursor, limit));
  }

  @GetMapping("/conversations/{conversationId}/turns")
  public CommonResponse<List<ConsoleAiApplicationService.TurnSummary>> turns(
      @PathVariable String conversationId,
      @RequestParam(required = false) Long beforeTurnNo,
      @RequestParam(defaultValue = "50") int limit) {
    authorizationService.assertAllowed();
    ConsoleRequestMetadata metadata = metadataResolver.current();
    return responseFactory.success(applicationService.turns(
        metadata.tenantId(), metadata.operatorId(), conversationId, beforeTurnNo, limit));
  }

  @GetMapping("/turns/by-client-id/{clientTurnId}")
  public CommonResponse<ConsoleAiApplicationService.ClientTurnSummary> turnStatus(
      @PathVariable UUID clientTurnId) {
    authorizationService.assertAllowed();
    ConsoleRequestMetadata metadata = metadataResolver.current();
    return responseFactory.success(applicationService.turnByClientId(
        metadata.tenantId(), metadata.operatorId(), clientTurnId));
  }

  @DeleteMapping("/conversations/{conversationId}")
  public CommonResponse<Void> deleteConversation(@PathVariable String conversationId) {
    authorizationService.assertAllowed();
    ConsoleRequestMetadata metadata = metadataResolver.current();
    applicationService.deleteConversation(
        metadata.tenantId(), metadata.operatorId(), conversationId);
    return responseFactory.success(null);
  }

  @GetMapping("/cost-summary")
  public CommonResponse<ConsoleAiApplicationService.AiCostSummary> costSummary(
      @RequestParam(required = false) String month) {
    authorizationService.assertAllowed();
    YearMonth billingMonth;
    try {
      billingMonth =
          EmptyChecks.isBlank(month) ? YearMonth.now(ZoneOffset.UTC) : YearMonth.parse(month);
    } catch (DateTimeParseException exception) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument_detail");
    }
    return responseFactory.success(
        applicationService.costSummary(metadataResolver.current().tenantId(), billingMonth));
  }
}
