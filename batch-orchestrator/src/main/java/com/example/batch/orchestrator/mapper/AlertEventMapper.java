package com.example.batch.orchestrator.mapper;

import com.example.batch.common.persistence.entity.AlertEventEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface AlertEventMapper {

    int insertOrMerge(AlertEventEntity entity);

    List<AlertEventEntity> selectByQuery(@Param("tenantId") String tenantId,
                                         @Param("severity") String severity,
                                         @Param("status") String status,
                                         @Param("alertType") String alertType,
                                         @Param("limit") Integer limit);
}
