package io.github.pinpols.batch.common.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Worker job_task 写入守护:拦截状态列与增删,放行登记的辅助列")
class WorkerJobTaskWriteGuardConventionTest {

  @Test
  @DisplayName("Worker 改 task_status 或增删 job_task 行被拦截")
  void shouldRejectStatusWritesAndRowLifecycle() {
    String xml = """
        <mapper>
          <update id="finishTask">
            update batch.job_task set task_status = #{status}, updated_at = now() where id = #{id}
          </update>
          <!-- update batch.job_task set ignored_in_comment = 1 -->
          <select id="finishViaCte" resultType="long">
            with moved as (
              update batch.job_task as t
              set <if test="x">lease_until = null,</if> task_status = 'SUCCESS'
              where t.id = #{id}
              returning t.id
            )
            select id from moved
          </select>
          <insert id="spawnTask">insert into batch.job_task(id) values (#{id})</insert>
          <delete id="dropTask">delete from batch.job_task where id = #{id}</delete>
        </mapper>
        """;

    List<String> violations =
        BaseWorkerJobTaskWriteGuardArchTest.jobTaskWriteViolations("TaskMapper.xml", xml, Set.of());

    assertThat(violations)
        .containsExactly(
            "TaskMapper#finishTask: set task_status",
            "TaskMapper#finishViaCte: set lease_until",
            "TaskMapper#finishViaCte: set task_status",
            "TaskMapper#spawnTask: insert into",
            "TaskMapper#dropTask: delete from");
  }

  @Test
  @DisplayName("登记列、updated_at 与表达式内逗号不误报,读 job_task 不受限")
  void shouldAllowRegisteredAuxiliaryColumns() {
    String xml = """
        <mapper>
          <update id="rotate">
            update batch.job_task t
            set process_staging_generation_id = #{generationId}::uuid,
                process_staging_batch_key = coalesce(#{batchKey}, t.process_staging_batch_key),
                updated_at = now()
            from batch.job_partition p
            where t.id = #{taskId} and t.task_status = 'RUNNING'
          </update>
          <update id="consume">
            with authorized as (select id from batch.job_task where id = #{id})
            update batch.job_task source_task
            set external_outcome_resolution_consumed_at = current_timestamp,
                updated_at = current_timestamp
            from authorized
            where source_task.id = authorized.id
          </update>
        </mapper>
        """;
    Set<String> allowed = Set.of(
        "process_staging_generation_id",
        "process_staging_batch_key",
        "external_outcome_resolution_consumed_at");

    assertThat(BaseWorkerJobTaskWriteGuardArchTest.jobTaskWriteViolations(
            "WorkerMapper.xml", xml, allowed))
        .isEmpty();
  }
}
