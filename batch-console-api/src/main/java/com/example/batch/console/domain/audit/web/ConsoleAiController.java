package com.example.batch.console.domain.audit.web;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.domain.audit.application.ai.ConsoleAiApplicationService;
import com.example.batch.console.domain.audit.web.response.AiChatResponse;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.Idempotent;
import com.example.batch.console.web.request.auth.AiChatRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
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

  /** AI 聊天一轮对话。 */
  @PostMapping("/chat")
  public CommonResponse<AiChatResponse> chat(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody AiChatRequest request) {
    return responseFactory.success(applicationService.chat(request, idempotencyKey));
  }
}
