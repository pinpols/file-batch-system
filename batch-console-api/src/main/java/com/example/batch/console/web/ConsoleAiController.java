package com.example.batch.console.web;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.application.ConsoleAiApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.request.AiChatRequest;
import com.example.batch.console.web.response.AiChatResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/console/ai")
@RequiredArgsConstructor
public class ConsoleAiController {

    private final ConsoleAiApplicationService applicationService;
    private final ConsoleResponseFactory responseFactory;

    @PostMapping("/chat")
    public CommonResponse<AiChatResponse> chat(
            @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody AiChatRequest request) {
        return responseFactory.success(applicationService.chat(request, idempotencyKey));
    }
}
