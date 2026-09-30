package io.github.pinpols.batch.console.domain.audit.web;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.application.contract.request.auth.AiChatRequest;
import io.github.pinpols.batch.console.domain.audit.application.ai.ConsoleAiApplicationService;
import io.github.pinpols.batch.console.domain.audit.application.contract.response.AiChatResponse;
import io.github.pinpols.batch.console.domain.audit.service.ConsoleAiAuthorizationService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import io.github.pinpols.batch.console.support.web.Idempotent;
import jakarta.validation.Valid;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import lombok.RequiredArgsConstructor;
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

  /** AI 聊天一轮对话。 */
  @PostMapping("/chat")
  public CommonResponse<AiChatResponse> chat(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody AiChatRequest request) {
    return responseFactory.success(applicationService.chat(request, idempotencyKey));
  }

  @GetMapping("/conversations")
  public CommonResponse<List<ConsoleAiApplicationService.ConversationSummary>> conversations(
      @RequestParam(defaultValue = "20") int limit) {
    authorizationService.assertAllowed();
    ConsoleRequestMetadata metadata = metadataResolver.current();
    return responseFactory.success(
        applicationService.conversations(metadata.tenantId(), metadata.operatorId(), limit));
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
