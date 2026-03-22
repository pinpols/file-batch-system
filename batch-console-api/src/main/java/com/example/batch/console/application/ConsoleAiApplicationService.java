package com.example.batch.console.application;

import com.example.batch.console.web.request.AiChatRequest;
import com.example.batch.console.web.response.AiChatResponse;

public interface ConsoleAiApplicationService {

    AiChatResponse chat(AiChatRequest request, String idempotencyKey);
}
