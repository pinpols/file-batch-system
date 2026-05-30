package com.example.batch.console.domain.file.application;

import com.example.batch.console.domain.file.web.request.ArchiveFileRequest;
import com.example.batch.console.domain.file.web.request.DeleteFileRequest;
import com.example.batch.console.domain.file.web.request.FileArrivalGroupActionRequest;
import com.example.batch.console.domain.file.web.request.PresignDownloadFileRequest;
import com.example.batch.console.domain.file.web.request.RedispatchFileRequest;
import com.example.batch.console.domain.file.web.response.ConsoleFileOperationResponse;
import com.example.batch.console.web.response.file.ConsolePresignDownloadResponse;
import java.util.Map;

/** 控制台文件治理应用服务：归档、删除、重派、预签名下载及到达组操作。 */
public interface ConsoleFileApplicationService {

  /** 归档文件记录。 */
  ConsoleFileOperationResponse archive(ArchiveFileRequest request, String idempotencyKey);

  /** 删除文件记录（逻辑或物理依编排器策略）。 */
  ConsoleFileOperationResponse delete(DeleteFileRequest request, String idempotencyKey);

  /** 重新派发文件处理任务。 */
  ConsoleFileOperationResponse redispatch(RedispatchFileRequest request, String idempotencyKey);

  /** 生成对象存储预签名下载 URL。 */
  ConsolePresignDownloadResponse presignDownload(
      PresignDownloadFileRequest request, String idempotencyKey);

  /** 对文件到达组执行批量动作（如确认、跳过等）。 */
  ConsoleFileOperationResponse operateArrivalGroup(
      FileArrivalGroupActionRequest request, String idempotencyKey);

  /** 生成对象存储预签名上传 URL（租户主动上传文件）。 */
  Map<String, Object> presignUpload(
      String tenantId, String channelCode, String fileName, String idempotencyKey);

  /** 租户确认文件到达。 */
  ConsoleFileOperationResponse confirmArrival(String tenantId, Long fileId, String idempotencyKey);
}
