package com.example.batch.console.mapper;

import com.example.batch.common.persistence.entity.AlertEventEntity;
import com.example.batch.console.domain.query.AlertEventQuery;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface AlertEventMapper {

    List<AlertEventEntity> selectByQuery(AlertEventQuery query);

    long countByQuery(AlertEventQuery query);

    long countByStatus(@Param("tenantId") String tenantId, @Param("status") String status);

    long countBySeverityAndStatus(@Param("tenantId") String tenantId,
                                  @Param("severity") String severity,
                                  @Param("status") String status);
}
