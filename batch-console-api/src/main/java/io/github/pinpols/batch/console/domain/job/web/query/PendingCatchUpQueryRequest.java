package io.github.pinpols.batch.console.domain.job.web.query;

import io.github.pinpols.batch.common.validation.ValidTenantId;
import io.github.pinpols.batch.console.web.query.PageQueryRequest;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = false)
@Data
public class PendingCatchUpQueryRequest extends PageQueryRequest {

  @ValidTenantId private String tenantId;

  @Size(max = 128, message = "jobCode too long (max 128)")
  private String jobCode;

  @Size(max = 128, message = "requestId too long (max 128)")
  private String requestId;

  /** 业务日期精确过滤(ISO yyyy-MM-dd),对应 trigger_request.biz_date。 */
  @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "bizDate must be ISO date yyyy-MM-dd")
  private String bizDate;

  /** 关键字模糊过滤(大小写不敏感):匹配 request_id / job_code / trace_id 任一。 */
  @Size(max = 128, message = "keyword too long (max 128)")
  private String keyword;
}
