package com.example.batch.console.web;

import com.example.batch.console.application.ConsoleFileApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.request.ArchiveFileRequest;
import com.example.batch.console.web.request.DeleteFileRequest;
import com.example.batch.console.web.request.FileArrivalGroupActionRequest;
import com.example.batch.console.web.request.PresignDownloadFileRequest;
import com.example.batch.console.web.request.RedispatchFileRequest;
import com.example.batch.console.web.response.ConsoleFileOperationResponse;
import com.example.batch.console.web.response.ConsolePresignDownloadResponse;
import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/console/files")
@RequiredArgsConstructor
public class ConsoleFileController {

    private final ConsoleFileApplicationService applicationService;
    private final ConsoleResponseFactory responseFactory;

    @PostMapping("/archive")
    public CommonResponse<ConsoleFileOperationResponse> archive(@RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                                                @Valid @RequestBody ArchiveFileRequest request) {
        return responseFactory.success(applicationService.archive(request, idempotencyKey));
    }

    @PostMapping("/delete")
    public CommonResponse<ConsoleFileOperationResponse> delete(@RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                                               @Valid @RequestBody DeleteFileRequest request) {
        return responseFactory.success(applicationService.delete(request, idempotencyKey));
    }

    @PostMapping("/redispatch")
    public CommonResponse<ConsoleFileOperationResponse> redispatch(@RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                                                   @Valid @RequestBody RedispatchFileRequest request) {
        return responseFactory.success(applicationService.redispatch(request, idempotencyKey));
    }

    @PostMapping("/presign-download")
    public CommonResponse<ConsolePresignDownloadResponse> presignDownload(@RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                                                          @Valid @RequestBody PresignDownloadFileRequest request) {
        return responseFactory.success(applicationService.presignDownload(request, idempotencyKey));
    }

    @PostMapping("/arrival-groups/action")
    public CommonResponse<ConsoleFileOperationResponse> operateArrivalGroup(@RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                                                            @Valid @RequestBody FileArrivalGroupActionRequest request) {
        return responseFactory.success(applicationService.operateArrivalGroup(request, idempotencyKey));
    }
}
