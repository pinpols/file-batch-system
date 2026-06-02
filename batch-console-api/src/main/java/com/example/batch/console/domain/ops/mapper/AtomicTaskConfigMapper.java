package com.example.batch.console.domain.ops.mapper;

import com.example.batch.console.domain.ops.entity.AtomicTaskConfigEntity;
import com.example.batch.console.domain.ops.param.AtomicTaskConfigCreateParam;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * R3-5 — {@code batch.atomic_task_config}(V165)读写 mapper。
 *
 * <p>多租 UNIQUE(tenant_id, task_type, name)由 DB 兜底;mapper WHERE 一律强制带 tenant_id, 见
 * MapperXmlTenantGuardArchTest 守护。
 */
public interface AtomicTaskConfigMapper {

  /** 按租户 + taskType 列配置,created_at 倒序。 */
  List<AtomicTaskConfigEntity> selectByTenantAndTaskType(
      @Param("tenantId") String tenantId, @Param("taskType") String taskType);

  AtomicTaskConfigEntity selectByTenantAndId(
      @Param("tenantId") String tenantId, @Param("id") Long id);

  AtomicTaskConfigEntity selectByTenantAndTaskTypeAndName(
      @Param("tenantId") String tenantId,
      @Param("taskType") String taskType,
      @Param("name") String name);

  /** INSERT 一条,useGeneratedKeys 回写 id 到 param。 */
  int insertAtomicTaskConfig(@Param("p") AtomicTaskConfigCreateParam param);
}
