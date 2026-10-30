package io.github.pinpols.batch.orchestrator.domain.query;

import io.github.pinpols.batch.common.utils.Texts;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/** 内部等待查询的 HTTP 参数；分页缺省值由查询服务统一补齐。 */
public record PersistentWaitReadQuery(
    @NotBlank String tenantId,
    String waitType,
    String waitStatus,
    Long workflowDefinitionId,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDateTo,
    Integer pageNo,
    Integer pageSize) {

  public static PersistentWaitReadQuery ofTenant(String tenantId) {
    return new PersistentWaitReadQuery(
        requireTenant(tenantId), null, null, null, null, null, null, null);
  }

  public static PersistentWaitReadQuery ofTenantAndWaitType(
      String tenantId, String waitType, int pageNo, int pageSize) {
    return new PersistentWaitReadQuery(
        requireTenant(tenantId), waitType, null, null, null, null, pageNo, pageSize);
  }

  private static String requireTenant(String tenantId) {
    if (!Texts.hasText(tenantId)) {
      throw new IllegalArgumentException("tenantId must not be blank");
    }
    return tenantId;
  }
}
