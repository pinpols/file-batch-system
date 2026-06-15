package com.example.batch.worker.atomic.spark;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.spi.task.TaskContext;
import com.example.batch.common.spi.task.TaskResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link SparkSubmitTaskExecutor} 骨架单测:只验 parse / 校验 / dry-run(**不 fork 真进程**)。 真正提交 spark-submit
 * 的端到端验证需有 Spark 环境,留给集成/手测。
 */
class SparkSubmitTaskExecutorTest {

  private SparkSubmitTaskExecutor executor;

  @BeforeEach
  void setUp() {
    SparkSubmitExecutorProperties props = new SparkSubmitExecutorProperties();
    props.setEnabled(true);
    props.setDefaultMaster("local[*]");
    executor = new SparkSubmitTaskExecutor(props);
  }

  private static TaskContext ctx(Map<String, Object> params) {
    return new TaskContext("t1", "SPARK_JOB", "task-1", "worker-1", params, Map.of());
  }

  @Test
  void taskType_isSparkSubmit() {
    assertThat(executor.taskType()).isEqualTo("spark_submit");
  }

  @Test
  void capability_isNonIdempotentAndCancellable() {
    assertThat(executor.capability().idempotent()).isFalse();
    assertThat(executor.capability().cancellable()).isTrue();
  }

  @Test
  void dryRun_buildsArgvWithoutForking() {
    TaskResult r =
        executor.execute(
            ctx(
                Map.of(
                    "appResource",
                    "s3a://jobs/etl.jar",
                    "mainClass",
                    "com.acme.Etl",
                    "sparkConf",
                    Map.of("spark.executor.memory", "2g"),
                    "appArgs",
                    List.of("--date", "2026-06-15"),
                    "dryRun",
                    true)));

    assertThat(r.success()).isTrue();
    assertThat(r.output()).containsEntry("plannedAction", "spark-submit");
    @SuppressWarnings("unchecked")
    List<String> argv = (List<String>) r.output().get("argv");
    assertThat(argv)
        .containsSubsequence("--master", "local[*]")
        .containsSubsequence("--class", "com.acme.Etl")
        .containsSubsequence("--conf", "spark.executor.memory=2g")
        .containsSubsequence("s3a://jobs/etl.jar", "--date", "2026-06-15");
  }

  @Test
  void missingAppResource_failsConfigInvalid() {
    TaskResult r = executor.execute(ctx(Map.of("dryRun", true)));
    assertThat(r.success()).isFalse();
    assertThat(r.output()).containsEntry("error_code", "CONFIG_INVALID");
  }

  @Test
  void appResourceNotInAllowlist_failsConfigInvalid() {
    SparkSubmitExecutorProperties props = new SparkSubmitExecutorProperties();
    props.setEnabled(true);
    props.setDefaultMaster("local[*]");
    props.setAppResourceAllowlist(List.of("s3a://approved/"));
    SparkSubmitTaskExecutor restricted = new SparkSubmitTaskExecutor(props);

    TaskResult r =
        restricted.execute(ctx(Map.of("appResource", "s3a://evil/x.jar", "dryRun", true)));
    assertThat(r.success()).isFalse();
    assertThat(r.output()).containsEntry("error_code", "CONFIG_INVALID");
  }

  @Test
  void confKeyNotInAllowlist_failsConfigInvalid() {
    SparkSubmitExecutorProperties props = new SparkSubmitExecutorProperties();
    props.setEnabled(true);
    props.setDefaultMaster("local[*]");
    props.setAllowedConfKeyPrefixes(Set.of("spark.sql."));
    SparkSubmitTaskExecutor restricted = new SparkSubmitTaskExecutor(props);

    TaskResult r =
        restricted.execute(
            ctx(
                Map.of(
                    "appResource",
                    "s3a://jobs/x.jar",
                    "sparkConf",
                    Map.of("spark.driver.extraJavaOptions", "-Devil=1"),
                    "dryRun",
                    true)));
    assertThat(r.success()).isFalse();
    assertThat(r.output()).containsEntry("error_code", "CONFIG_INVALID");
  }
}
