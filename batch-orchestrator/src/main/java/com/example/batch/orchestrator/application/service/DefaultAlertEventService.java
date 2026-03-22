package com.example.batch.orchestrator.application.service;

import com.example.batch.common.utils.AlertFingerprints;
import com.example.batch.orchestrator.domain.dto.AlertEmitRequest;
import com.example.batch.orchestrator.domain.entity.AlertEventEntity;
import com.example.batch.orchestrator.mapper.AlertEventMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class DefaultAlertEventService implements AlertEventService {

    private final AlertEventMapper alertEventMapper;
    private final MeterRegistry meterRegistry;

    @Override
    @Transactional
    public void emit(AlertEmitRequest request) {
        if (request == null || !StringUtils.hasText(request.tenantId()) || !StringUtils.hasText(request.alertType())) {
            return;
        }
        String serviceName = StringUtils.hasText(request.serviceName()) ? request.serviceName() : "batch-orchestrator";
        String severity = StringUtils.hasText(request.severity()) ? request.severity() : "WARN";
        String title = StringUtils.hasText(request.title()) ? request.title() : request.alertType();
        String resourceKey = StringUtils.hasText(request.resourceKey()) ? request.resourceKey() : title;
        String fingerprint = AlertFingerprints.build(request.tenantId(), request.alertType(), resourceKey);

        AlertEventEntity entity = new AlertEventEntity();
        entity.setTenantId(request.tenantId());
        entity.setServiceName(serviceName);
        entity.setAlertType(request.alertType());
        entity.setSeverity(severity);
        entity.setTitle(title);
        entity.setDetailJson(request.detailJson());
        entity.setDedupFingerprint(fingerprint);
        entity.setTraceId(request.traceId());
        entity.setStatus("OPEN");

        alertEventMapper.insertOrMerge(entity);

        meterRegistry.counter("batch.alert.events",
                Tags.of("alert_type", request.alertType(), "severity", severity)).increment();
    }
}
