package io.github.pinpols.batch.console.domain.ops.web.request;

import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import lombok.Data;

/**
 * ADR-022 v0.1 forensic 取证请求（POST /api/console/forensic/export）。
 *
 * <p>v0.1 范围：(tenantId, bizDate 范围, 可选 jobCodes) → BUNDLE / JSON / CSV bundle 同步生成。
 */
@Data
public class ForensicExportRequest {

  @ValidTenantId private String tenantId;

  @NotNull private LocalDate bizDateFrom;

  @NotNull private LocalDate bizDateTo;

  /** 可选 — 留空表示全部 jobCode；最多 100 个，单个 ≤ 128 字符。 */
  @Size(max = 100, message = "jobCodes too many (max 100)")
  private List<@Size(max = 128) String> jobCodes;

  /** BUNDLE（默认）/ JSON / CSV。 */
  @Pattern(regexp = "^(BUNDLE|JSON|CSV)$", message = "exportFormat must be BUNDLE / JSON / CSV")
  private String exportFormat;

  /** 操作人 ID（审计必填）。 */
  @Size(max = 128)
  private String requestedBy;
}
