package io.github.pinpols.batch.console.application.contract.request.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Data;

@Data
public class AiChatRequest {

  private String tenantId;

  // 防超长撞下游 audit 表 session_id VARCHAR(128)(截断/写入报错)。
  @Size(max = 128)
  private String sessionId;

  @NotBlank
  @Size(max = 32)
  private String contextVersion = "v1";

  @NotBlank
  private String prompt;

  private List<UUID> attachmentIds;

  private UUID clientTurnId;

  private AiPageContextRequest pageContext;

  /** 兼容旧版请求字段；v1 仅接受 pageType、objectType 和 objectId。 */
  private Map<String, Object> context = new LinkedHashMap<>();
}
