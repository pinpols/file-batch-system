package com.example.batch.console.application;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;

/** 控制台文件下载应用服务：按租户与审批上下文流式返回对象存储中的文件内容。 */
public interface ConsoleFileDownloadApplicationService {

    /** 下载指定文件；可选审批 ID 用于下载审计与权限校验。 */
    ResponseEntity<InputStreamResource> download(String tenantId, Long fileId, String approvalId);

    /** 导出指定文件的错误记录为 CSV。 */
    ResponseEntity<InputStreamResource> exportFileErrors(
            String tenantId, Long fileId, String errorStage);
}
