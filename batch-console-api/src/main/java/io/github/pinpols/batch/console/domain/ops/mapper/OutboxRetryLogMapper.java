package io.github.pinpols.batch.console.domain.ops.mapper;

import io.github.pinpols.batch.console.domain.ops.query.OutboxRetryLogQuery;
import io.github.pinpols.batch.console.domain.ops.view.OutboxRetryLogRow;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface OutboxRetryLogMapper {

  List<OutboxRetryLogRow> selectByQuery(OutboxRetryLogQuery query);

  long countByQuery(OutboxRetryLogQuery query);

  long countByStatuses(
      @Param("tenantId") String tenantId, @Param("statuses") List<String> statuses);
}
