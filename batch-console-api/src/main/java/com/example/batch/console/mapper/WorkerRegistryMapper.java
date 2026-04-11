package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.WorkerRegistryEntity;
import com.example.batch.console.domain.query.WorkerRegistryQuery;

import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface WorkerRegistryMapper {

    List<WorkerRegistryEntity> selectByQuery(WorkerRegistryQuery query);

    long countByQuery(WorkerRegistryQuery query);

    long countByStatus(@Param("tenantId") String tenantId, @Param("status") String status);
}
