package io.github.pinpols.batch.orchestrator.application.service;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.domain.query.WaitRelationshipQuery;
import io.github.pinpols.batch.orchestrator.domain.query.WaitRelationshipRow;
import io.github.pinpols.batch.orchestrator.mapper.WaitRelationshipMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WaitRelationshipReadService {
  private final WaitRelationshipMapper mapper;
  private final WaitRelationshipSanitizer sanitizer;

  @Transactional(readOnly = true)
  public WaitRelationshipPage list(WaitRelationshipQuery query) {
    int size =
        EmptyChecks.isNull(query.pageSize()) ? 100 : Math.min(100, Math.max(1, query.pageSize()));
    mapper.setTenantContext(query.tenantId());
    List<WaitRelationshipRow> rows = mapper.selectPage(query, size + 1);
    boolean hasMore = rows.size() > size;
    List<WaitRelationshipRow> items =
        hasMore ? List.copyOf(rows.subList(0, size)) : List.copyOf(rows);
    items.forEach(sanitizer::sanitize);
    WaitRelationshipRow last = hasMore ? items.get(items.size() - 1) : null;
    return new WaitRelationshipPage(
        items,
        hasMore,
        EmptyChecks.isNull(last) ? null : last.getWaitType(),
        EmptyChecks.isNull(last) ? null : last.getId());
  }

  public record WaitRelationshipPage(
      List<WaitRelationshipRow> items,
      boolean hasMore,
      String nextCursorType,
      String nextCursorId) {}
}
