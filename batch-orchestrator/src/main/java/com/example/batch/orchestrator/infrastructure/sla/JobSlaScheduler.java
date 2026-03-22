package com.example.batch.orchestrator.infrastructure.sla;

import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.config.SlaGovernanceProperties;
import com.example.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.mapper.JobExecutionLogMapper;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobSlaScheduler {

    private final JobInstanceMapper jobInstanceMapper;
    private final JobExecutionLogMapper jobExecutionLogMapper;
    private final SlaGovernanceProperties properties;
    private final MeterRegistry meterRegistry;
    private final AtomicLong violationCount = new AtomicLong();

    @jakarta.annotation.PostConstruct
    void initializeMeters() {
        meterRegistry.gauge("batch.job.sla.violation.count", violationCount);
    }

    @Scheduled(fixedDelayString = "${batch.sla.poll-interval-millis:30000}")
    public void scanViolations() {
        if (!properties.isEnabled()) {
            return;
        }
        violationCount.set(jobInstanceMapper.countSlaViolationCandidates());
        List<JobInstanceEntity> candidates = jobInstanceMapper.selectSlaViolationCandidates(properties.getBatchSize());
        Instant now = Instant.now();
        for (JobInstanceEntity candidate : candidates) {
            if (candidate == null || candidate.getId() == null || candidate.getTenantId() == null) {
                continue;
            }
            if (jobInstanceMapper.markSlaAlerted(candidate.getTenantId(), candidate.getId(), now) <= 0) {
                continue;
            }
            JobExecutionLogEntity logEntity = new JobExecutionLogEntity();
            logEntity.setTenantId(candidate.getTenantId());
            logEntity.setJobInstanceId(candidate.getId());
            logEntity.setLogLevel("WARN");
            logEntity.setLogType("ALARM");
            logEntity.setTraceId(candidate.getTraceId());
            logEntity.setMessage(buildMessage(candidate, now));
            logEntity.setDetailRef("job-sla");
            logEntity.setExtraJson(JsonUtils.toJson(buildExtra(candidate, now)));
            jobExecutionLogMapper.insert(logEntity);
            log.warn("job SLA violation detected: tenantId={}, jobInstanceId={}, instanceNo={}, extra={}",
                    candidate.getTenantId(), candidate.getId(), candidate.getInstanceNo(), buildExtra(candidate, now));
        }
    }

    private String buildMessage(JobInstanceEntity candidate, Instant now) {
        Map<String, Object> extra = buildExtra(candidate, now);
        return "job SLA violated: " + extra.get("violationReason");
    }

    private Map<String, Object> buildExtra(JobInstanceEntity candidate, Instant now) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("instanceNo", candidate.getInstanceNo());
        extra.put("jobCode", candidate.getJobCode());
        extra.put("instanceStatus", candidate.getInstanceStatus());
        extra.put("deadlineAt", candidate.getDeadlineAt());
        extra.put("expectedDurationSeconds", candidate.getExpectedDurationSeconds());
        if (candidate.getDeadlineAt() != null && candidate.getDeadlineAt().isBefore(now)) {
            extra.put("violationReason", "DEADLINE_EXCEEDED");
            extra.put("deadlineDelaySeconds", Duration.between(candidate.getDeadlineAt(), now).getSeconds());
        } else if (candidate.getExpectedDurationSeconds() != null
                && candidate.getExpectedDurationSeconds() > 0
                && candidate.getStartedAt() != null) {
            Instant expectedFinish = candidate.getStartedAt().plusSeconds(candidate.getExpectedDurationSeconds());
            extra.put("violationReason", "EXPECTED_DURATION_EXCEEDED");
            extra.put("expectedFinishAt", expectedFinish);
            extra.put("durationDelaySeconds", Duration.between(expectedFinish, now).getSeconds());
        } else {
            extra.put("violationReason", "UNKNOWN");
        }
        return extra;
    }
}
