package io.github.pinpols.batch.console.web.request.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

@Data
public class AiChatRequest {

  private String tenantId;

  // 防超长撞下游 audit 表 session_id VARCHAR(128)(截断/写入报错)。
  @Size(max = 128)
  private String sessionId;

  @NotBlank private String prompt;

  private Map<String, Object> context = new LinkedHashMap<>();
}
