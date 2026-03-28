package com.example.batch.console.mapper;

import com.example.batch.console.domain.query.OutboxRetryLogQuery;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface OutboxRetryLogMapper {

    List<Map<String, Object>> selectByQuery(OutboxRetryLogQuery query);

    long countByStatuses(@Param("tenantId") String tenantId, @Param("statuses") List<String> statuses);
}
