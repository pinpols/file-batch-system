package com.example.batch.console.domain.audit.application;

import com.example.batch.common.model.PageResponse;
import com.example.batch.console.domain.audit.mapper.OperationAuditMapper;
import com.example.batch.console.domain.audit.mapper.OperationAuditMapper.AuditRow;
import com.example.batch.console.domain.audit.web.query.OperationAuditQueryRequest;
import com.example.batch.console.domain.audit.web.response.ConsoleOperationAuditResponse;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通用控制台用户操作审计查询服务。
 *
 * <p>读路径走只读副本(`@Transactional(readOnly = true)` + 默认 DataSource 路由策略)。
 *
 * <p><b>租户隔离</b>:必须经 {@link ConsoleTenantGuard#resolveTenant(String)} 解析后再下发 Mapper, 否则租户用户传
 * null/blank tenantId 即可绕过 SQL 租户过滤拿全租户审计(P0 越权)。 全局角色(ADMIN/AUDITOR/CONFIG_ADMIN)必须显式传
 * tenantId,租户角色 JWT 强制覆盖请求值。
 */
@Service
@RequiredArgsConstructor
public class OperationAuditQueryService {

  private final OperationAuditMapper mapper;
  private final ConsoleTenantGuard tenantGuard;

  @Transactional(readOnly = true)
  public PageResponse<ConsoleOperationAuditResponse> query(OperationAuditQueryRequest req) {
    String tenantId = tenantGuard.resolveTenant(req.getTenantId());
    int pageNo = req.getPageNo() == null ? 1 : req.getPageNo();
    int pageSize = req.getPageSize() == null ? 20 : req.getPageSize();
    int offset = (pageNo - 1) * pageSize;
    long total =
        mapper.count(
            tenantId,
            req.getAggregateType(),
            req.getAggregateId(),
            req.getAction(),
            req.getOperatorId(),
            req.getResult(),
            req.getTraceId(),
            req.getStartTime(),
            req.getEndTime());
    List<AuditRow> rows =
        mapper.query(
            tenantId,
            req.getAggregateType(),
            req.getAggregateId(),
            req.getAction(),
            req.getOperatorId(),
            req.getResult(),
            req.getTraceId(),
            req.getStartTime(),
            req.getEndTime(),
            offset,
            pageSize);
    List<ConsoleOperationAuditResponse> items = rows.stream().map(this::toResponse).toList();
    return new PageResponse<>(total, pageNo, pageSize, items);
  }

  private ConsoleOperationAuditResponse toResponse(AuditRow r) {
    return new ConsoleOperationAuditResponse(
        r.id(),
        r.tenantId(),
        r.aggregateType(),
        r.aggregateId(),
        r.action(),
        r.operatorId(),
        r.operatorRole(),
        r.result(),
        r.errorCode(),
        r.errorMessage(),
        r.paramsJson(),
        r.traceId(),
        r.requestId(),
        r.ipHash(),
        r.uaHash(),
        r.eventVersion(),
        r.createdAt());
  }
}
