package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.WorkerRegistryEntity;
import com.example.batch.console.domain.query.WorkerRegistryQuery;
import java.util.List;

public interface WorkerRegistryMapper {

    List<WorkerRegistryEntity> selectByQuery(WorkerRegistryQuery query);
}
