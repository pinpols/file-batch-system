package com.example.batch.console.web;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.application.ConsoleFileApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.request.ArchiveFileRequest;
import com.example.batch.console.web.request.DeleteFileRequest;
import com.example.batch.console.web.request.FileArrivalGroupActionRequest;
import com.example.batch.console.web.request.PresignDownloadFileRequest;
import com.example.batch.console.web.request.RedispatchFileRequest;
import com.example.batch.console.web.response.ConsoleFileOperationResponse;
import com.example.batch.console.web.response.ConsolePresignDownloadResponse;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 控制台文件治理 REST：归档、删除、重派、预签名下载、到达组操作（需幂等键）。 */
@RestController
@Validated
@RequestMapping("/api/console/files")
@RequiredArgsConstructor
public class ConsoleFileController {

    private final ConsoleFileApplicationService applicationService;
    private final ConsoleResponseFactory responseFactory;

    /** 归档文件。 */
    @PostMapping("/archive")
    public CommonResponse<ConsoleFileOperationResponse> archive(
            @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody ArchiveFileRequest request) {
        return responseFactory.success(applicationService.archive(request, idempotencyKey));
    }

    /** 删除文件记录。 */
    @PostMapping("/delete")
    public CommonResponse<ConsoleFileOperationResponse> delete(
            @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody DeleteFileRequest request) {
        return responseFactory.success(applicationService.delete(request, idempotencyKey));
    }

    /** 重新派发文件任务。 */
    @PostMapping("/redispatch")
    public CommonResponse<ConsoleFileOperationResponse> redispatch(
            @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody RedispatchFileRequest request) {
        return responseFactory.success(applicationService.redispatch(request, idempotencyKey));
    }

    /** 获取预签名下载地址。 */
    @PostMapping("/presign-download")
    public CommonResponse<ConsolePresignDownloadResponse> presignDownload(
            @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody PresignDownloadFileRequest request) {
        return responseFactory.success(applicationService.presignDownload(request, idempotencyKey));
    }

    /** 对到达组执行批量动作。 */
    @PostMapping("/arrival-groups/action")
    public CommonResponse<ConsoleFileOperationResponse> operateArrivalGroup(
            @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody FileArrivalGroupActionRequest request) {
        return responseFactory.success(
                applicationService.operateArrivalGroup(request, idempotencyKey));
    }

    /** 获取预签名上传地址（租户主动上传文件）。 */
    @PostMapping("/presign-upload")
    public CommonResponse<Map<String, Object>> presignUpload(
            @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @RequestParam("tenantId") String tenantId,
            @RequestParam("channelCode") String channelCode,
            @RequestParam("fileName") String fileName) {
        return responseFactory.success(
                applicationService.presignUpload(tenantId, channelCode, fileName, idempotencyKey));
    }

    /** 租户确认文件已到达。 */
    @PostMapping("/{fileId}/confirm-arrival")
    public CommonResponse<ConsoleFileOperationResponse> confirmArrival(
            @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @PathVariable Long fileId,
            @RequestParam("tenantId") String tenantId) {
        return responseFactory.success(
                applicationService.confirmArrival(tenantId, fileId, idempotencyKey));
    }
}
