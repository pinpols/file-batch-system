package io.github.pinpols.batch.orchestrator.controller;

import java.time.Instant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.orchestrator.application.service.capacity.CapacityProfileGroupBy;
import io.github.pinpols.batch.orchestrator.application.service.capacity.CapacityProfileReport;
import io.github.pinpols.batch.orchestrator.application.service.capacity.CapacityProfileService;

/** P2 cost profile 内部只读 API。 */
@RestController
@RequestMapping("/internal/orchestrator/capacity-profile")
@RequiredArgsConstructor
public class CapacityProfileController {

    private final CapacityProfileService capacityProfileService;

    @GetMapping
    public CommonResponse<CapacityProfileReport> query(
            @RequestParam("tenantId") String tenantId,
            @RequestParam(value = "from", required = false) Instant from,
            @RequestParam(value = "to", required = false) Instant to,
            @RequestParam(value = "groupBy", required = false) String groupBy,
            @RequestParam(value = "limit", required = false) Integer limit) {
        CapacityProfileGroupBy grouping = CapacityProfileGroupBy.fromNullable(groupBy);
        CapacityProfileReport report = capacityProfileService.query(tenantId, from, to, grouping, limit);
        return CommonResponse.success(report);
    }
}
