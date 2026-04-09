package com.example.batch.console.web;

import com.example.batch.console.application.ConsoleOpsApplicationService;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.service.ConsoleKafkaLagQueryService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.response.ConsoleOpsSummaryResponse;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台运维总览 REST：租户维度运行摘要。
 */
@RestController
@Validated
@RequestMapping("/api/console/ops")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN')")
@RequiredArgsConstructor
public class ConsoleOpsController {

    private final ConsoleOpsApplicationService opsApplicationService;
    private final ConsoleResponseFactory responseFactory;
    private final ConsoleKafkaLagQueryService kafkaLagQueryService;

    /** 租户运维摘要。 */
    @GetMapping("/summary")
    public CommonResponse<ConsoleOpsSummaryResponse> summary(@RequestParam @NotBlank String tenantId) {
        return responseFactory.success(opsApplicationService.summary(tenantId));
    }

    /** Kafka consumer group 积压查询。 */
    @GetMapping("/kafka-lag")
    public CommonResponse<List<Map<String, Object>>> kafkaConsumerLag(
            @RequestParam(value = "groupId", required = false) String groupId) {
        return responseFactory.success(kafkaLagQueryService.consumerGroupLags(groupId));
    }
}
