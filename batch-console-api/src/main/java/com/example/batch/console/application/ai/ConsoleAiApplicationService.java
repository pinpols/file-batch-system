package com.example.batch.console.application.ai;

import com.example.batch.console.web.request.auth.AiChatRequest;
import com.example.batch.console.web.response.auth.AiChatResponse;

/** 控制台 AI 对话应用服务：基于 Spring AI 的聊天与审计落库。 */
public interface ConsoleAiApplicationService {

  /** 处理一轮 AI 对话请求（幂等键用于防重复计费/重复写入）。 */
  AiChatResponse chat(AiChatRequest request, String idempotencyKey);
}
