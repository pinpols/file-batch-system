package com.example.batch.console.application.audit;

import com.example.batch.common.model.PageResponse;
import com.example.batch.console.mapper.OperationAuditMapper;
import com.example.batch.console.mapper.OperationAuditMapper.AuditRow;
import com.example.batch.console.web.query.OperationAuditQueryRequest;
import com.example.batch.console.web.response.ops.ConsoleOperationAuditResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通用控制台用户操作审计查询服务。
 *
 * <p>读路径走只读副本(`@Transactional(readOnly = true)` + 默认 DataSource 路由策略)。
 */
@Service
@RequiredArgsConstructor
public class OperationAuditQueryService {

  private final OperationAuditMapper mapper;

  @Transactional(readOnly = true)
  public PageResponse<ConsoleOperationAuditResponse> query(OperationAuditQueryRequest req) {
    int pageNo = req.getPageNo() == null ? 1 : req.getPageNo();
    int pageSize = req.getPageSize() == null ? 20 : req.getPageSize();
    int offset = (pageNo - 1) * pageSize;
    long total =
        mapper.count(
            req.getTenantId(),
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
            req.getTenantId(),
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
