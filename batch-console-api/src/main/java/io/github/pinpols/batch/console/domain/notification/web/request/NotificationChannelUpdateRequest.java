package io.github.pinpols.batch.console.domain.notification.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 通知渠道更新请求。channelCode 来自路径参数（body 中若携带将被忽略），故不在此 DTO 内。
 *
 * <p>mapper update 为全量覆盖（无动态 <code>&lt;if&gt;</code>），channelName / channelType 必填。
 */
@Data
public class NotificationChannelUpdateRequest {

  @NotBlank
  @Size(max = 128)
  private String channelName;

  @NotBlank
  @Size(max = 32)
  private String channelType;

  private String configJson;

  private Boolean enabled = Boolean.TRUE;
}
