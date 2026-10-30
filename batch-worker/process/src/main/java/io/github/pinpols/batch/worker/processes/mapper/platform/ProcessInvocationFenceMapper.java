package io.github.pinpols.batch.worker.processes.mapper.platform;

import io.github.pinpols.batch.worker.processes.param.ProcessInvocationFenceRotateCurrentStagingGenerationParam;
import io.github.pinpols.batch.worker.processes.query.ProcessInvocationFenceCurrentInvocationIdQuery;
import io.github.pinpols.batch.worker.processes.query.ProcessInvocationFenceCurrentStagingGenerationQuery;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** PROCESS 调用身份、当前暂存代际和清理围栏的平台库访问。 */
public interface ProcessInvocationFenceMapper {

  List<String> listCleanupTenants();

  Long selectTaskInstanceId(@Param("tenantId") String tenantId, @Param("taskId") Long taskId);

  /** 从热表或归档任务读取当前暂存代际。 */
  String selectTaskCurrentStagingGeneration(
      @Param("tenantId") String tenantId, @Param("taskId") Long taskId);

  /** 获取实例级事务围栏，协调执行、回收与报告。 */
  Integer acquireInstanceFence(
      @Param("tenantId") String tenantId, @Param("jobInstanceId") Long jobInstanceId);

  /** 读取当前运行中分区的 invocation 身份。 */
  String selectCurrentInvocationId(ProcessInvocationFenceCurrentInvocationIdQuery parameters);

  /** 仅对持有当前 invocation 的运行任务切换暂存代际。 */
  int rotateCurrentStagingGeneration(
      ProcessInvocationFenceRotateCurrentStagingGenerationParam parameters);

  /** 仅在调用身份仍有效时读取当前暂存代际。 */
  String selectCurrentStagingGeneration(
      ProcessInvocationFenceCurrentStagingGenerationQuery parameters);
}
