package io.github.pinpols.batch.console.domain.audit.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.domain.audit.application.contract.query.OperationAuditQueryRequest;
import io.github.pinpols.batch.console.domain.audit.application.contract.response.ConsoleOperationAuditResponse;
import io.github.pinpols.batch.console.domain.audit.mapper.OperationAuditMapper;
import io.github.pinpols.batch.console.domain.audit.mapper.OperationAuditMapper.AuditRow;
import io.github.pinpols.batch.console.domain.audit.query.OperationAuditCountQuery;
import io.github.pinpols.batch.console.domain.audit.query.OperationAuditQuery;
import io.github.pinpols.batch.console.domain.audit.support.AuditParamRedactor;
import io.github.pinpols.batch.console.shared.query.ConsoleQuerySupport;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.shared.query.TenantScope;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通用控制台用户操作审计查询服务。
 *
 * <p>读路径走只读副本(`@Transactional(readOnly = true)` + 默认 DataSource 路由策略)。
 *
 * <p><b>租户隔离</b>:必须经 {@link TenantIdResolver#resolveTenant(String)} 解析后再下发 Mapper, 否则租户用户传
 * null/blank tenantId 即可绕过 SQL 租户过滤拿全租户审计(P0 越权)。 全局角色(ADMIN/AUDITOR/CONFIG_ADMIN)必须显式传
 * tenantId,租户角色 JWT 强制覆盖请求值。
 */
@Service
@RequiredArgsConstructor
public class OperationAuditQueryService {

  private final OperationAuditMapper mapper;
  private final TenantIdResolver tenantGuard;
  private final ObjectMapper objectMapper;

  @Transactional(readOnly = true)
  public PageResponse<ConsoleOperationAuditResponse> query(OperationAuditQueryRequest req) {
    String resolvedTenantId = tenantGuard.resolveTenant(req.getTenantId());
    String tenantId = TenantScope.requireTenant(resolvedTenantId);
    mapper.bindTenant(tenantId);
    boolean cursorMode = EmptyChecks.isNotNull(req.getCursor());
    int requestedPageNo = EmptyChecks.isNull(req.getPageNo()) ? 1 : req.getPageNo();
    int requestedPageSize = EmptyChecks.isNull(req.getPageSize()) ? 20 : req.getPageSize();
    PageRequest pageRequest = new PageRequest(cursorMode ? 1 : requestedPageNo, requestedPageSize);
    OperationAuditQuery operationAuditQuery = OperationAuditQuery.builder()
        .tenantId(tenantId)
        .aggregateType(req.getAggregateType())
        .aggregateId(req.getAggregateId())
        .action(req.getAction())
        .operatorId(req.getOperatorId())
        .result(req.getResult())
        .traceId(req.getTraceId())
        .startTime(req.getStartTime())
        .endTime(req.getEndTime())
        .cursorId(ConsoleQuerySupport.decodeCursorId(req.getCursor()))
        .cursorMode(cursorMode)
        .pageRequest(pageRequest)
        .build();
    List<AuditRow> rows = mapper.query(operationAuditQuery);
    if (cursorMode) {
      return ConsoleQuerySupport.cursorPage(pageRequest, rows, this::toResponse, AuditRow::id);
    }
    OperationAuditCountQuery operationAuditCountQuery = OperationAuditCountQuery.builder()
        .tenantId(tenantId)
        .aggregateType(req.getAggregateType())
        .aggregateId(req.getAggregateId())
        .action(req.getAction())
        .operatorId(req.getOperatorId())
        .result(req.getResult())
        .traceId(req.getTraceId())
        .startTime(req.getStartTime())
        .endTime(req.getEndTime())
        .cursorId(null)
        .build();
    long total = mapper.count(operationAuditCountQuery);
    List<ConsoleOperationAuditResponse> items =
        rows.stream().map(this::toResponse).toList();
    return new PageResponse<>(total, pageRequest.pageNo(), pageRequest.pageSize(), items);
  }

  private ConsoleOperationAuditResponse toResponse(AuditRow r) {
    Long id = r.id();
    String tenantId = r.tenantId();
    String aggregateType = r.aggregateType();
    String aggregateId = r.aggregateId();
    String action = r.action();
    String operatorId = r.operatorId();
    String operatorRole = r.operatorRole();
    String result = r.result();
    String errorCode = r.errorCode();
    String errorMessage = r.errorMessage();
    String paramsJson = r.paramsJson();
    String safeParamsJson = AuditParamRedactor.redactJson(objectMapper, paramsJson);
    String traceId = r.traceId();
    String requestId = r.requestId();
    String ipHash = r.ipHash();
    String uaHash = r.uaHash();
    Integer eventVersion = r.eventVersion();
    Instant createdAt = r.createdAt();
    return ConsoleOperationAuditResponse.builder()
        .id(id)
        .tenantId(tenantId)
        .aggregateType(aggregateType)
        .aggregateId(aggregateId)
        .action(action)
        .operatorId(operatorId)
        .operatorRole(operatorRole)
        .result(result)
        .errorCode(errorCode)
        .errorMessage(errorMessage)
        .params(safeParamsJson)
        .traceId(traceId)
        .requestId(requestId)
        .ipHash(ipHash)
        .uaHash(uaHash)
        .eventVersion(eventVersion)
        .createdAt(createdAt)
        .build();
  }
}
