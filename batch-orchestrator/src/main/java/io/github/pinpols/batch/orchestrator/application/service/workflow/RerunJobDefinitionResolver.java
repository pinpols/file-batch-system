package io.github.pinpols.batch.orchestrator.application.service.workflow;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.ConfigVersionPolicy;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.utils.EmptyValues;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.view.JobDefinitionView;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;

/** 为 JOB_RERUN 根 Workflow 启动选择不可变的 Job 定义快照。 */
@Component
public class RerunJobDefinitionResolver {

    private static final String OPERATION_JOB_RERUN = "JOB_RERUN";
    private static final String PARAM_OPERATION_TYPE = "operationType";
    private static final String PARAM_PARENT_INSTANCE_ID = "parentInstanceId";
    private static final String PARAM_CONFIG_VERSION_POLICY = "_rerunConfigVersionPolicy";
    private static final String PARAM_CONFIG_VERSION = "_rerunConfigVersion";

    private final JobInstanceMapper jobInstanceMapper;
    private final WorkflowRunMapper workflowRunMapper;

    public RerunJobDefinitionResolver(JobInstanceMapper jobInstanceMapper, WorkflowRunMapper workflowRunMapper) {
        this.jobInstanceMapper = jobInstanceMapper;
        this.workflowRunMapper = workflowRunMapper;
    }

    public JobDefinitionView resolve(LaunchRequest request, String jobCode, JobDefinitionView latestDefinition) {
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        if (!OPERATION_JOB_RERUN.equals(params.get(PARAM_OPERATION_TYPE))) {
            return latestDefinition;
        }
        Long sourceInstanceId = positiveLong(params.get(PARAM_PARENT_INSTANCE_ID));
        if (sourceInstanceId == null) {
            throw conflict("error.rerun.source_instance_required");
        }
        JobInstanceEntity source = jobInstanceMapper.selectById(request.tenantId(), sourceInstanceId);
        boolean sourceInstanceDoesNotMatchRequestedJob = source == null
                || !request.tenantId().equals(source.getTenantId())
                || !jobCode.equals(source.getJobCode())
                || latestDefinition == null
                || latestDefinition.id() == null
                || !latestDefinition.id().equals(source.getJobDefinitionId());
        if (sourceInstanceDoesNotMatchRequestedJob) {
            throw conflict("error.rerun.source_instance_mismatch");
        }

        String rawPolicy = text(params.get(PARAM_CONFIG_VERSION_POLICY));
        ConfigVersionPolicy policy = EmptyValues.isBlank(rawPolicy)
                ? ConfigVersionPolicy.USE_ORIGINAL_CONFIG
                : ConfigVersionPolicy.fromCodeOrNull(rawPolicy);
        if (policy == null) {
            throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.rerun.config_version_policy_invalid");
        }
        return switch (policy) {
            case USE_LATEST_CONFIG -> latestDefinition;
            case USE_ORIGINAL_CONFIG -> originalDefinition(request.tenantId(), source, jobCode);
            case USE_SPECIFIED_VERSION -> specifiedDefinition(request.tenantId(), source, jobCode, params);
        };
    }

    private JobDefinitionView originalDefinition(String tenantId, JobInstanceEntity source, String jobCode) {
        WorkflowRunEntity sourceRun = workflowRunMapper.selectByNodeJobInstanceId(tenantId, source.getId());
        JobDefinitionView snapshot = jobDefinitionFromRun(sourceRun, jobCode, source.getJobDefinitionId());
        if (snapshot == null || !Objects.equals(snapshot.version(), source.getJobDefinitionVersion())) {
            throw conflict("error.rerun.original_config_unavailable");
        }
        return snapshot;
    }

    private JobDefinitionView specifiedDefinition(
            String tenantId, JobInstanceEntity source, String jobCode, Map<String, Object> params) {
        Integer version = positiveInteger(params.get(PARAM_CONFIG_VERSION));
        if (version == null) {
            throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.rerun.config_version_required");
        }
        WorkflowRunEntity versionedRun = workflowRunMapper.selectLatestJobDefinitionSnapshot(
                tenantId, source.getJobDefinitionId(), jobCode, version);
        JobDefinitionView snapshot = jobDefinitionFromRun(versionedRun, jobCode, source.getJobDefinitionId());
        if (snapshot == null || !version.equals(snapshot.version())) {
            throw BizException.of(ResultCode.NOT_FOUND, "error.rerun.config_version_unavailable", version);
        }
        return snapshot;
    }

    private JobDefinitionView jobDefinitionFromRun(WorkflowRunEntity run, String jobCode, Long jobDefinitionId) {
        WorkflowRunExecutionSnapshot snapshot = WorkflowRunExecutionSnapshot.from(run);
        if (snapshot == null) {
            return null;
        }
        List<JobDefinitionView> matches = snapshot.jobDefinitions().values().stream()
                .filter(job -> jobCode.equals(job.jobCode()) && jobDefinitionId.equals(job.id()))
                .toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private Long positiveLong(Object value) {
        try {
            String numericText = text(value);
            BigDecimal decimalValue = new BigDecimal(numericText);
            long parsed = decimalValue.longValueExact();
            return parsed > 0 ? parsed : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private Integer positiveInteger(Object value) {
        try {
            String numericText = text(value);
            BigDecimal decimalValue = new BigDecimal(numericText);
            int parsed = decimalValue.intValueExact();
            return parsed > 0 ? parsed : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private BizException conflict(String messageKey) {
        return BizException.of(ResultCode.STATE_CONFLICT, messageKey);
    }
}
