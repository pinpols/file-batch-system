package com.example.batch.console.domain.ops.mapper;

import com.example.batch.console.domain.ops.entity.WorkerRegistryEntity;
import com.example.batch.console.domain.ops.query.WorkerRegistryQuery;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface WorkerRegistryMapper {

  List<WorkerRegistryEntity> selectByQuery(WorkerRegistryQuery query);

  long countByQuery(WorkerRegistryQuery query);

  long countByStatus(@Param("tenantId") String tenantId, @Param("status") String status);

  /** ADR-035 P4 "我的 Worker" — 按 tenant 列 SDK 自托管 worker(is_self_hosted=true),heartbeat_at desc。 */
  List<WorkerRegistryEntity> selectSelfHostedByTenant(@Param("tenantId") String tenantId);

  long countSelfHostedByTenant(@Param("tenantId") String tenantId);
}
