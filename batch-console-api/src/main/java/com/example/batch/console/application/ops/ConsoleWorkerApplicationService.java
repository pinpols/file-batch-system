package com.example.batch.console.application.ops;

import com.example.batch.console.web.request.ops.DrainWorkerRequest;
import com.example.batch.console.web.request.ops.ForceOfflineWorkerRequest;
import com.example.batch.console.web.response.ops.ConsoleWorkerClaimedTaskResponse;
import com.example.batch.console.web.response.ops.ConsoleWorkerRegistryResponse;
import java.util.List;

/** 控制台 Worker 运维应用服务：优雅排空、强制下线及已认领任务查询。 */
public interface ConsoleWorkerApplicationService {

  /** 将 Worker 置为排空状态，停止接收新任务并逐步完成在途任务。 */
  ConsoleWorkerRegistryResponse drain(
      String workerCode, DrainWorkerRequest request, String idempotencyKey);

  /** 强制 Worker 下线并更新注册状态。 */
  ConsoleWorkerRegistryResponse forceOffline(
      String workerCode, ForceOfflineWorkerRequest request, String idempotencyKey);

  /** 立即接管 Worker 的在途任务并将其置为退役状态。 */
  ConsoleWorkerRegistryResponse takeover(
      String workerCode, ForceOfflineWorkerRequest request, String idempotencyKey);

  /** 查询指定 Worker 当前已认领的任务列表。 */
  List<ConsoleWorkerClaimedTaskResponse> claimedTasks(String tenantId, String workerCode);

  /** 预热指定 Worker：发送预热信号使其提前建立连接池/缓存。 */
  ConsoleWorkerRegistryResponse warmup(String workerCode, String tenantId, String idempotencyKey);
}
