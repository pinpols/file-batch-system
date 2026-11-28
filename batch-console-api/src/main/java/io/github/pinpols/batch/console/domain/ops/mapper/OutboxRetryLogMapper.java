package io.github.pinpols.batch.console.domain.ops.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import io.github.pinpols.batch.console.domain.ops.query.OutboxRetryLogQuery;
import io.github.pinpols.batch.console.domain.ops.view.OutboxRetryLogRow;

public interface OutboxRetryLogMapper {

    List<OutboxRetryLogRow> selectByQuery(OutboxRetryLogQuery query);

    long countByQuery(OutboxRetryLogQuery query);

    long countByStatuses(@Param("tenantId") String tenantId, @Param("statuses") List<String> statuses);
}
