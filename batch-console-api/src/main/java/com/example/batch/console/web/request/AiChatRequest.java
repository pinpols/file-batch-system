package com.example.batch.console.web.request;

import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

@Data
public class AiChatRequest {

  private String tenantId;
  private String sessionId;

  @NotBlank private String prompt;

  private Map<String, Object> context = new LinkedHashMap<>();
}
