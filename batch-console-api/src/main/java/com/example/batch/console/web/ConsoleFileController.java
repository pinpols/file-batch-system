package com.example.batch.console.web;

import com.example.batch.console.application.ConsoleFileApplicationService;
import com.example.batch.console.support.ConsoleResponseFactory;
import com.example.batch.console.web.request.ArchiveFileRequest;
import com.example.batch.console.web.request.DeleteFileRequest;
import com.example.batch.console.web.request.FileArrivalGroupActionRequest;
import com.example.batch.console.web.request.PresignDownloadFileRequest;
import com.example.batch.console.web.request.RedispatchFileRequest;
import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/console/files")
@RequiredArgsConstructor
public class ConsoleFileController {

    private final ConsoleFileApplicationService applicationService;
    private final ConsoleResponseFactory responseFactory;

    @PostMapping("/archive")
    public CommonResponse<String> archive(@RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                          @Valid @RequestBody ArchiveFileRequest request) {
        return responseFactory.success(applicationService.archive(request, idempotencyKey));
    }

    @PostMapping("/delete")
    public CommonResponse<String> delete(@RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                         @Valid @RequestBody DeleteFileRequest request) {
        return responseFactory.success(applicationService.delete(request, idempotencyKey));
    }

    @PostMapping("/redispatch")
    public CommonResponse<String> redispatch(@RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                             @Valid @RequestBody RedispatchFileRequest request) {
        return responseFactory.success(applicationService.redispatch(request, idempotencyKey));
    }

    @PostMapping("/presign-download")
    public CommonResponse<String> presignDownload(@RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                                    @Valid @RequestBody PresignDownloadFileRequest request) {
        return responseFactory.success(applicationService.presignDownload(request, idempotencyKey));
    }

    @PostMapping("/arrival-groups/action")
    public CommonResponse<String> operateArrivalGroup(@RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                                      @Valid @RequestBody FileArrivalGroupActionRequest request) {
        return responseFactory.success(applicationService.operateArrivalGroup(request, idempotencyKey));
    }
}
