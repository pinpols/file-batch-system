package io.github.pinpols.batch.worker.processes.mapper.platform;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Platform-database lock and current-claim lookup used to fence PROCESS business-database stages. */
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

  /** Returns the current generation only when the task is represented in hot or cold storage. */
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

  /** Must remain byte-for-byte equivalent in key semantics to JobInstanceMapper's advisory fence. */
  @Select("""
      select 1
      from (
        select pg_advisory_xact_lock(hashtext(#{tenantId} || ':' || #{jobInstanceId}::text))
      ) locked
      """)
  Integer acquireInstanceFence(
      @Param("tenantId") String tenantId, @Param("jobInstanceId") Long jobInstanceId);

  /** Returns the current invocation only while both task and partition are still running. */
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
      @Param("tenantId") String tenantId,
      @Param("taskId") Long taskId,
      @Param("jobInstanceId") Long jobInstanceId,
      @Param("jobPartitionId") Long jobPartitionId,
      @Param("workerId") String workerId);

  /** Durably starts a new generation before the business database transaction can write staging. */
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
      @Param("tenantId") String tenantId,
      @Param("taskId") Long taskId,
      @Param("jobInstanceId") Long jobInstanceId,
      @Param("jobPartitionId") Long jobPartitionId,
      @Param("workerId") String workerId,
      @Param("invocationId") String invocationId,
      @Param("batchKey") String batchKey,
      @Param("generationId") String generationId);

  /** Reads the generation after a checkpointed COMPUTE is skipped. */
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
      @Param("tenantId") String tenantId,
      @Param("taskId") Long taskId,
      @Param("jobInstanceId") Long jobInstanceId,
      @Param("jobPartitionId") Long jobPartitionId,
      @Param("workerId") String workerId,
      @Param("invocationId") String invocationId);
}
