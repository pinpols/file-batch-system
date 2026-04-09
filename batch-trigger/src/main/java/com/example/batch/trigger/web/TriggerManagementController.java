package com.example.batch.trigger.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.trigger.domain.TriggerRegistrationService;
import com.example.batch.trigger.domain.TriggerStatusInfo;
import com.example.batch.trigger.infrastructure.TriggerGracefulShutdown;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/triggers/management")
@RequiredArgsConstructor
public class TriggerManagementController {

    private final TriggerRegistrationService triggerRegistrationService;
    private final TriggerGracefulShutdown gracefulShutdown;

    @GetMapping("/list")
    public CommonResponse<List<TriggerStatusInfo>> list() {
        return CommonResponse.success(triggerRegistrationService.listRegisteredTriggers());
    }

    @PostMapping("/register")
    public CommonResponse<Map<String, String>> register(@RequestParam("tenantId") String tenantId,
                                                         @RequestParam("jobCode") String jobCode) {
        triggerRegistrationService.registerByJobCode(tenantId, jobCode);
        return CommonResponse.success(Map.of("tenantId", tenantId, "jobCode", jobCode, "status", "REGISTERED"));
    }

    @PostMapping("/unregister")
    public CommonResponse<Map<String, String>> unregister(@RequestParam("tenantId") String tenantId,
                                                           @RequestParam("jobCode") String jobCode) {
        triggerRegistrationService.unregisterByJobCode(tenantId, jobCode);
        return CommonResponse.success(Map.of("tenantId", tenantId, "jobCode", jobCode, "status", "UNREGISTERED"));
    }

    @PostMapping("/pause")
    public CommonResponse<Map<String, String>> pause(@RequestParam("tenantId") String tenantId,
                                                      @RequestParam("jobCode") String jobCode) {
        triggerRegistrationService.pauseByJobCode(tenantId, jobCode);
        return CommonResponse.success(Map.of("tenantId", tenantId, "jobCode", jobCode, "status", "PAUSED"));
    }

    @PostMapping("/resume")
    public CommonResponse<Map<String, String>> resume(@RequestParam("tenantId") String tenantId,
                                                       @RequestParam("jobCode") String jobCode) {
        triggerRegistrationService.resumeByJobCode(tenantId, jobCode);
        return CommonResponse.success(Map.of("tenantId", tenantId, "jobCode", jobCode, "status", "NORMAL"));
    }

    @GetMapping("/scheduler-status")
    public CommonResponse<Map<String, String>> schedulerStatus() {
        String status = triggerRegistrationService.schedulerStatus();
        return CommonResponse.success(Map.of("status", status));
    }

    @PostMapping("/pause-all")
    public CommonResponse<Map<String, String>> pauseAll() {
        triggerRegistrationService.pauseAll();
        return CommonResponse.success(Map.of("status", "ALL_PAUSED"));
    }

    @PostMapping("/resume-all")
    public CommonResponse<Map<String, String>> resumeAll() {
        triggerRegistrationService.resumeAll();
        return CommonResponse.success(Map.of("status", "ALL_RESUMED"));
    }

    @GetMapping("/drain/status")
    public CommonResponse<Map<String, Object>> drainStatus() throws Exception {
        return CommonResponse.success(gracefulShutdown.status());
    }

    @PostMapping("/drain/enable")
    public CommonResponse<Map<String, Object>> enableDrain() throws Exception {
        gracefulShutdown.startDraining("manual-enable");
        return CommonResponse.success(gracefulShutdown.status());
    }

    @PostMapping("/drain/disable")
    public CommonResponse<Map<String, Object>> disableDrain() throws Exception {
        gracefulShutdown.stopDraining("manual-disable");
        return CommonResponse.success(gracefulShutdown.status());
    }
}
