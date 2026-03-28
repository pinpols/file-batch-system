package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.WorkerRegistryEntity;
import com.example.batch.console.domain.query.WorkerRegistryQuery;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface WorkerRegistryMapper {

    List<WorkerRegistryEntity> selectByQuery(WorkerRegistryQuery query);

    long countByStatus(@Param("tenantId") String tenantId, @Param("status") String status);
}
