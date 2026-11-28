package io.github.pinpols.batch.worker.imports.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.worker.imports.runtime.ImportEventArrivalService;

/** 对象存储事件源的内部到达通知入口。 */
@RestController
@RequestMapping("/internal/import/events")
@RequiredArgsConstructor
public class ImportEventArrivalController {

    private final ImportEventArrivalService arrivalService;

    @PostMapping("/object-arrival")
    public CommonResponse<ImportEventArrivalResponse> objectArrival(
            @RequestBody(required = false) ObjectArrivalNotification notification) {
        ImportEventArrivalService.Result result = arrivalService.onArrival(
                notification == null ? null : notification.getTenantId(),
                notification == null ? null : notification.getBucket(),
                notification == null ? null : notification.getObjectKey());
        ImportEventArrivalResponse response = new ImportEventArrivalResponse(result.triggered(), result.reason());
        return CommonResponse.success(response);
    }
}
