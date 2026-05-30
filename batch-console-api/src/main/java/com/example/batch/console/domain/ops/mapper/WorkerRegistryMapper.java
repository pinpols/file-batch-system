package com.example.batch.console.domain.ops.mapper;

import com.example.batch.console.domain.ops.entity.WorkerRegistryEntity;
import com.example.batch.console.domain.ops.query.WorkerRegistryQuery;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface WorkerRegistryMapper {

  List<WorkerRegistryEntity> selectByQuery(WorkerRegistryQuery query);

  long countByQuery(WorkerRegistryQuery query);

  long countByStatus(@Param("tenantId") String tenantId, @Param("status") String status);
}
