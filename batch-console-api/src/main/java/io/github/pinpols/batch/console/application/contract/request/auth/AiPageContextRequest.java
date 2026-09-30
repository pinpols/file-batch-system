package io.github.pinpols.batch.console.application.contract.request.auth;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** v1 页面引用字段；服务端后续查询前必须重新读取数据并完成授权校验。 */
@Data
public class AiPageContextRequest {

  @Size(max = 64)
  private String pageType;

  @Size(max = 64)
  private String objectType;

  @Size(max = 128)
  private String objectId;
}
