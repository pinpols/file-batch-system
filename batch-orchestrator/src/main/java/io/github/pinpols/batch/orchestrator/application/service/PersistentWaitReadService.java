package io.github.pinpols.batch.orchestrator.application.service;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.domain.query.PersistentWaitReadFilter;
import io.github.pinpols.batch.orchestrator.domain.query.PersistentWaitReadQuery;
import io.github.pinpols.batch.orchestrator.domain.query.PersistentWaitReadRow;
import io.github.pinpols.batch.orchestrator.mapper.PersistentWaitReadMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Console 运维等待查询，只投影安全字段并在租户事务内执行。 */
@Service
@RequiredArgsConstructor
public class PersistentWaitReadService {
  private static final int MAX_PAGE_SIZE = 100;

  private final PersistentWaitReadMapper mapper;

  @Transactional(readOnly = true)
  public PersistentWaitPage list(PersistentWaitReadQuery query) {
    int normalizedPageNo = Math.max(1, EmptyChecks.isNull(query.pageNo()) ? 1 : query.pageNo());
    int requestedPageSize = EmptyChecks.isNull(query.pageSize()) ? 15 : query.pageSize();
    int normalizedPageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, requestedPageSize));
    long offset = ((long) normalizedPageNo - 1L) * normalizedPageSize;
    PersistentWaitReadFilter filter = new PersistentWaitReadFilter(
        query.tenantId(),
        blankToNull(query.waitType()),
        blankToNull(query.waitStatus()),
        query.workflowDefinitionId(),
        query.bizDateFrom(),
        query.bizDateTo());
    mapper.setTenantContext(filter.tenantId());
    List<PersistentWaitReadRow> items = mapper.selectPage(filter, normalizedPageSize, offset);
    long total = mapper.count(filter);
    return new PersistentWaitPage(items, normalizedPageNo, normalizedPageSize, total);
  }

  private static String blankToNull(String value) {
    return EmptyChecks.isNull(value) || EmptyChecks.isBlank(value) ? null : value;
  }

  public record PersistentWaitPage(
      List<PersistentWaitReadRow> items, int pageNo, int pageSize, long total) {}
}
