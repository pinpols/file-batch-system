package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.query.WaitRelationshipQuery;
import io.github.pinpols.batch.orchestrator.domain.query.WaitRelationshipRow;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface WaitRelationshipMapper {
  String setTenantContext(@Param("tenantId") String tenantId);

  List<WaitRelationshipRow> selectPage(
      @Param("query") WaitRelationshipQuery query, @Param("limit") int limit);
}
