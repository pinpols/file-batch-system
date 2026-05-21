package com.example.batch.testing;

import com.example.batch.common.enums.DeadLetterReplayStatus;
import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.enums.WorkflowEdgeType;
import com.example.batch.common.enums.WorkflowRunStatus;

/**
 * Test 层常量集中地 —— 替换分散的 `"SUCCESS"` / `"FAILED"` / `"NEW"` 等业务状态字符串字面量。
 *
 * <p>**为什么不直接 `JobInstanceStatus.SUCCESS.code()`**:跨 enum 同义字面量(JobInstanceStatus.SUCCESS /
 * WorkflowRunStatus.SUCCESS / WorkflowEdgeType.SUCCESS 都 = "SUCCESS")在测试断言里频繁出现,集中常量化让
 * 「测试在断言哪个域的状态」一目了然(`JobInstance.SUCCESS` vs `Edge.SUCCESS` vs `Workflow.SUCCESS`)。
 *
 * <p>**只放真正高频且语义清晰**的常量(audit 显示 ≥ 3 处出现);零散的 1-2 处场景继续直接用 `.code()`,
 * 避免常量爆炸。
 *
 * <p>本类**只在 test-jar 暴露**,生产代码禁引用。
 */
public final class TestConstants {

  private TestConstants() {}

  /** {@link JobInstanceStatus} 速记。 */
  public static final class JobInstance {
    public static final String SUCCESS = JobInstanceStatus.SUCCESS.code();
    public static final String FAILED = JobInstanceStatus.FAILED.code();
    public static final String RUNNING = JobInstanceStatus.RUNNING.code();
    public static final String CREATED = JobInstanceStatus.CREATED.code();

    private JobInstance() {}
  }

  /** {@link WorkflowRunStatus} 速记。 */
  public static final class Workflow {
    public static final String SUCCESS = WorkflowRunStatus.SUCCESS.code();
    public static final String FAILED = WorkflowRunStatus.FAILED.code();
    public static final String RUNNING = WorkflowRunStatus.RUNNING.code();
    public static final String CREATED = WorkflowRunStatus.CREATED.code();
    public static final String TERMINATED = WorkflowRunStatus.TERMINATED.code();

    private Workflow() {}
  }

  /** {@link WorkflowEdgeType} 速记(注意跟 Workflow.SUCCESS 同字面量但语义不同)。 */
  public static final class Edge {
    public static final String SUCCESS = WorkflowEdgeType.SUCCESS.code();
    public static final String FAILURE = WorkflowEdgeType.FAILURE.code();
    public static final String CONDITION = WorkflowEdgeType.CONDITION.code();
    public static final String ALWAYS = WorkflowEdgeType.ALWAYS.code();

    private Edge() {}
  }

  /** {@link OutboxPublishStatus} 速记。 */
  public static final class Outbox {
    public static final String NEW = OutboxPublishStatus.NEW.code();
    public static final String PUBLISHING = OutboxPublishStatus.PUBLISHING.code();
    public static final String PUBLISHED = OutboxPublishStatus.PUBLISHED.code();
    public static final String FAILED = OutboxPublishStatus.FAILED.code();
    public static final String GIVE_UP = OutboxPublishStatus.GIVE_UP.code();

    private Outbox() {}
  }

  /** {@link DeadLetterReplayStatus} 速记(DLQ replay 场景特别多 "NEW")。 */
  public static final class DeadLetter {
    public static final String NEW = DeadLetterReplayStatus.NEW.code();
    public static final String REPLAYING = DeadLetterReplayStatus.REPLAYING.code();
    public static final String SUCCESS = DeadLetterReplayStatus.SUCCESS.code();
    public static final String FAILED = DeadLetterReplayStatus.FAILED.code();
    public static final String GIVE_UP = DeadLetterReplayStatus.GIVE_UP.code();

    private DeadLetter() {}
  }
}
