package io.github.pinpols.batch.orchestrator.application.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import io.github.pinpols.batch.orchestrator.domain.query.WaitRelationshipQuery;
import io.github.pinpols.batch.orchestrator.domain.query.WaitRelationshipRow;
import io.github.pinpols.batch.orchestrator.mapper.WaitRelationshipMapper;

@Service
@RequiredArgsConstructor
public class WaitRelationshipReadService {

    /** 等待关系单页条数上限，未指定 pageSize 时也取该值。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final WaitRelationshipMapper mapper;
    private final WaitRelationshipSanitizer sanitizer;

    @Transactional(readOnly = true)
    public WaitRelationshipPage list(WaitRelationshipQuery query) {
        int size = query.pageSize() == null ? MAX_PAGE_SIZE : Math.min(MAX_PAGE_SIZE, Math.max(1, query.pageSize()));
        mapper.setTenantContext(query.tenantId());
        List<WaitRelationshipRow> rows = mapper.selectPage(query, size + 1);
        boolean hasMore = rows.size() > size;
        List<WaitRelationshipRow> items = hasMore ? List.copyOf(rows.subList(0, size)) : List.copyOf(rows);
        items.forEach(sanitizer::sanitize);
        WaitRelationshipRow last = hasMore ? items.get(items.size() - 1) : null;
        return new WaitRelationshipPage(
                items, hasMore, last == null ? null : last.getWaitType(), last == null ? null : last.getId());
    }

    public record WaitRelationshipPage(
            List<WaitRelationshipRow> items, boolean hasMore, String nextCursorType, String nextCursorId) {}
}
