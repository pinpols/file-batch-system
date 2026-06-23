package io.github.pinpols.batch.console.domain.audit.application.ai;

import io.github.pinpols.batch.console.domain.audit.web.response.AiChatResponse;
import io.github.pinpols.batch.console.web.request.auth.AiChatRequest;

/** 控制台 AI 对话应用服务：基于 Spring AI 的聊天与审计写入数据库。 */
public interface ConsoleAiApplicationService {

  /** 处理一轮 AI 对话请求（幂等键用于防重复计费/重复写入）。 */
  AiChatResponse chat(AiChatRequest request, String idempotencyKey);
}
