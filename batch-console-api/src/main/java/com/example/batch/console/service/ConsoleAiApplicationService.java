package com.example.batch.console.service;

import com.example.batch.console.domain.request.AiChatRequest;
import com.example.batch.console.domain.response.AiChatResponse;

public interface ConsoleAiApplicationService {

    AiChatResponse chat(AiChatRequest request, String idempotencyKey);
}
