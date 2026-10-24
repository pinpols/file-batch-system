package io.github.pinpols.batch.worker.processes.mapper.platform;

import io.github.pinpols.batch.worker.processes.param.ProcessInvocationFenceRotateCurrentStagingGenerationParam;
import io.github.pinpols.batch.worker.processes.query.ProcessInvocationFenceCurrentInvocationIdQuery;
import io.github.pinpols.batch.worker.processes.query.ProcessInvocationFenceCurrentStagingGenerationQuery;


import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 持久化当前租约所有者即将运行非幂等执行器这一事实。 */
public interface ProcessInvocationFenceMapper {

  @Select("select tenant_id from batch.tenant order by tenant_id")
  List<String> listCleanupTenants();

  @Select("""
      select job_instance_id
      from (
        select job_instance_id from batch.job_task
        where tenant_id = #{tenantId} and id = #{taskId}
        union all
        select job_instance_id from archive.job_task_archive
        where tenant_id = #{tenantId} and id = #{taskId}
      ) task
      limit 1
      """)
  Long selectTaskInstanceId(@Param("tenantId") String tenantId, @Param("taskId") Long taskId);

  /** 仅在可恢复的跨日依赖等待期间保留的加密启动上下文。 */
  @Select("""
      select process_staging_generation_id::text
      from (
        select process_staging_generation_id from batch.job_task
        where tenant_id = #{tenantId} and id = #{taskId}
        union all
        select process_staging_generation_id from archive.job_task_archive
        where tenant_id = #{tenantId} and id = #{taskId}
      ) task
      limit 1
      """)
  String selectTaskCurrentStagingGeneration(
      @Param("tenantId") String tenantId, @Param("taskId") Long taskId);

  /** 已持久化准入前等待记录的生命周期；终态记录不能重新入队。 */
  @Select("""
      select 1
      from (
        select pg_advisory_xact_lock(hashtext(#{tenantId} || ':' || #{jobInstanceId}::text))
      ) locked
      """)
  Integer acquireInstanceFence(
      @Param("tenantId") String tenantId, @Param("jobInstanceId") Long jobInstanceId);

  /** 可选的 CAS 来源状态，供不得覆盖并发变更的操作使用。 */
  @Select("""
      select p.current_invocation_id
      from batch.job_task t
      join batch.job_partition p
        on p.tenant_id = t.tenant_id
       and p.job_instance_id = t.job_instance_id
       and p.id = t.job_partition_id
      where t.tenant_id = #{tenantId}
        and t.id = #{taskId}
        and t.job_instance_id = #{jobInstanceId}
        and t.job_partition_id = #{jobPartitionId}
        and t.task_status = 'RUNNING'
        and p.partition_status = 'RUNNING'
        and p.worker_code = #{workerId}
      """)
  String selectCurrentInvocationId(
      ProcessInvocationFenceCurrentInvocationIdQuery parameters);

  /** 批次日运维人员明确豁免、尚未解决的输入义务快照。 */
  @Update("""
      update batch.job_task t
      set process_staging_generation_id = #{generationId}::uuid,
          process_staging_batch_key = #{batchKey},
          updated_at = now()
      from batch.job_partition p
      where t.tenant_id = #{tenantId}
        and t.id = #{taskId}
        and t.job_instance_id = #{jobInstanceId}
        and t.job_partition_id = #{jobPartitionId}
        and t.task_status = 'RUNNING'
        and p.tenant_id = t.tenant_id
        and p.id = t.job_partition_id
        and p.job_instance_id = t.job_instance_id
        and p.partition_status = 'RUNNING'
        and p.worker_code = #{workerId}
        and p.current_invocation_id = #{invocationId}
      """)
  int rotateCurrentStagingGeneration(
      ProcessInvocationFenceRotateCurrentStagingGenerationParam parameters);

  /** 用于评估就绪状态并持久化延迟启动的不可变依赖边快照。 */
  @Select("""
      select t.process_staging_generation_id::text
      from batch.job_task t
      join batch.job_partition p
        on p.tenant_id = t.tenant_id
       and p.job_instance_id = t.job_instance_id
       and p.id = t.job_partition_id
      where t.tenant_id = #{tenantId}
        and t.id = #{taskId}
        and t.job_instance_id = #{jobInstanceId}
        and t.job_partition_id = #{jobPartitionId}
        and t.task_status = 'RUNNING'
        and p.partition_status = 'RUNNING'
        and p.worker_code = #{workerId}
        and p.current_invocation_id = #{invocationId}
      """)
  String selectCurrentStagingGeneration(
      ProcessInvocationFenceCurrentStagingGenerationQuery parameters);
}
