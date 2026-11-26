package io.github.pinpols.batch.orchestrator.application.service.governance;

import io.github.pinpols.batch.common.enums.TaskErrorCodes;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskOutcomeService;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 把「非幂等外部动作已开始、但租约结束前没有回报」的任务收口为结果不明。
 *
 * <p>自动租约回收与手工接管/强制下线必须采用同一裁定：已有未确认外部结果时不能重新入队，否则新 Worker 会再次执行外部动作。
 * 结果经 {@link TaskOutcomeService} 进入既有的人工核对/补偿流程，而不是复用普通重试。
 */
@Component
@RequiredArgsConstructor
public class UnknownExternalOutcomeCloser {

    static final String OUTCOME_UNKNOWN_MESSAGE =
            "Worker lease expired after non-idempotent execution started; external outcome requires reconciliation.";

    private final JobPartitionMapper jobPartitionMapper;
    // TaskOutcomeService 依赖 RetryGovernanceService；用 provider 断开“接管→收口→结果服务→接管”的构造环，
    // 只在真正收口时解析。
    private final ObjectProvider<TaskOutcomeService> taskOutcomeServiceProvider;

    /**
     * 以 partition 当前 invocation 关闭任务。
     *
     * @return {@code true} 表示结果已落库；{@code false} 表示缺少当前 invocation 或结果未记录，调用方应让本轮回收失败并等待下一轮重试
     */
    public boolean close(JobTaskEntity task) {
        JobPartitionEntity partition = jobPartitionMapper.selectById(task.getTenantId(), task.getJobPartitionId());
        if (EmptyChecks.isNull(partition)
                || EmptyChecks.isNull(partition.getCurrentInvocationId())
                || EmptyChecks.isBlank(partition.getCurrentInvocationId())) {
            return false;
        }
        TaskOutcomeCommand command = TaskOutcomeCommand.builder()
                .tenantId(task.getTenantId())
                .taskId(task.getId())
                .workerId(task.getAssignedWorkerCode())
                .success(false)
                .errorCode(TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN)
                .errorMessage(OUTCOME_UNKNOWN_MESSAGE)
                .partitionInvocationId(partition.getCurrentInvocationId())
                .build();
        TaskOutcomeService taskOutcomeService = taskOutcomeServiceProvider.getObject();
        JobTaskEntity outcome = taskOutcomeService.applyTaskOutcome(command);
        return EmptyChecks.isNotNull(outcome);
    }
}
