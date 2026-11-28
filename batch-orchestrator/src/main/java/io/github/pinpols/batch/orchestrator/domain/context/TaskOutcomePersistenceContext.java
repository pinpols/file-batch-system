package io.github.pinpols.batch.orchestrator.domain.context;

import lombok.Data;

import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;

/** Worker 回报入口所需的 task 与所属 partition 一致性快照。 */
@Data
public class TaskOutcomePersistenceContext {

    private JobTaskEntity task;
    private JobPartitionEntity partition;
}
