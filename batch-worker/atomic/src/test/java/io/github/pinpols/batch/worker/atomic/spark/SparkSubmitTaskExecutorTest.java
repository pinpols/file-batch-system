package io.github.pinpols.batch.worker.atomic.spark;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.spi.task.TaskContext;
import io.github.pinpols.batch.common.spi.task.TaskResult;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SparkSubmitTaskExecutor} 骨架单测:只验 parse / 校验 / dry-run(**不 fork 真进程**)。 真正提交 spark-submit
 * 的端到端验证需有 Spark 环境,留给集成/手测。
 */
@DisplayName("外部计算提交执行器: 参数解析, 白名单校验与 dry-run 计划")
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
  @DisplayName("任务类型应标识为外部计算提交, 供注册表按能力路由")
  void taskType_isSparkSubmit() {
    assertThat(executor.taskType()).isEqualTo("spark_submit");
  }

  @Test
  @DisplayName("能力声明应为非幂等且可取消, 避免重试引发重复提交")
  void capability_isNonIdempotentAndCancellable() {
    assertThat(executor.capability().idempotent()).isFalse();
    assertThat(executor.capability().cancellable()).isTrue();
  }

  @Test
  @DisplayName("dry-run 下应给出完整启动参数计划, 包含运行模式, 主类, 配置与业务参数")
  void dryRun_buildsArgvWithoutForking() {
    TaskResult r = executor.execute(ctx(Map.of(
        "appResource",
        "s3a://jobs/etl.jar",
        "mainClass",
        "com.acme.Etl",
        "sparkConf",
        Map.of("spark.executor.memory", "2g"),
        "appArgs",
        List.of("--date", "2026-06-15"),
        PipelineRuntimeKeys.DRY_RUN,
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
  @DisplayName("缺少应用资源时应判失败, 并返回配置无效错误码")
  void missingAppResource_failsConfigInvalid() {
    TaskResult r = executor.execute(ctx(Map.of(PipelineRuntimeKeys.DRY_RUN, true)));
    assertThat(r.success()).isFalse();
    assertThat(r.output()).containsEntry("error_code", "CONFIG_INVALID");
  }

  @Test
  @DisplayName("指定输出路径时应注入配置项并在结果中回显输出地址")
  void outputPath_injectsConfAndReturnsOutputUri() {
    TaskResult r = executor.execute(ctx(Map.of(
        "appResource",
        "s3a://jobs/etl.jar",
        "outputPath",
        "s3a://batch-out/t1/SPARK_JOB/2026-06-15/",
        PipelineRuntimeKeys.DRY_RUN,
        true)));

    assertThat(r.success()).isTrue();
    assertThat(r.output()).containsEntry("outputUri", "s3a://batch-out/t1/SPARK_JOB/2026-06-15/");
    @SuppressWarnings("unchecked")
    List<String> argv = (List<String>) r.output().get("argv");
    assertThat(argv)
        .containsSubsequence(
            "--conf", "spark.batch.outputPath=s3a://batch-out/t1/SPARK_JOB/2026-06-15/");
  }

  @Test
  @DisplayName("应用资源不在白名单内时应判失败, 并返回配置无效错误码")
  void appResourceNotInAllowlist_failsConfigInvalid() {
    SparkSubmitExecutorProperties props = new SparkSubmitExecutorProperties();
    props.setEnabled(true);
    props.setDefaultMaster("local[*]");
    props.setAppResourceAllowlist(List.of("s3a://approved/"));
    SparkSubmitTaskExecutor restricted = new SparkSubmitTaskExecutor(props);

    TaskResult r = restricted.execute(
        ctx(Map.of("appResource", "s3a://evil/x.jar", PipelineRuntimeKeys.DRY_RUN, true)));
    assertThat(r.success()).isFalse();
    assertThat(r.output()).containsEntry("error_code", "CONFIG_INVALID");
  }

  @Test
  @DisplayName("提交模式为集群模式时应判失败, 并在提示中说明受限模式")
  void clusterDeployMode_failsConfigInvalid() {
    TaskResult r = executor.execute(ctx(Map.of(
        "appResource",
        "s3a://jobs/x.jar",
        "deployMode",
        "cluster",
        PipelineRuntimeKeys.DRY_RUN,
        true)));
    assertThat(r.success()).isFalse();
    assertThat(r.output()).containsEntry("error_code", "CONFIG_INVALID");
    assertThat(r.message()).contains("cluster");
  }

  @Test
  @DisplayName("集群地址不在允许前缀内时应判失败, 并返回配置无效错误码")
  void masterNotInAllowlist_failsConfigInvalid() {
    SparkSubmitExecutorProperties props = new SparkSubmitExecutorProperties();
    props.setEnabled(true);
    props.setAllowedMasterPrefixes(List.of("yarn", "k8s://"));
    SparkSubmitTaskExecutor restricted = new SparkSubmitTaskExecutor(props);

    TaskResult r = restricted.execute(ctx(Map.of(
        "appResource",
        "s3a://jobs/x.jar",
        "master",
        "spark://attacker:7077",
        PipelineRuntimeKeys.DRY_RUN,
        true)));
    assertThat(r.success()).isFalse();
    assertThat(r.output()).containsEntry("error_code", "CONFIG_INVALID");
  }

  @Test
  @DisplayName("业务参数不符合允许的正则形状时应判失败, 阻止注入类取值")
  void appArgNotMatchingRegexAllowlist_failsConfigInvalid() {
    SparkSubmitExecutorProperties props = new SparkSubmitExecutorProperties();
    props.setEnabled(true);
    props.setDefaultMaster("local[*]");
    props.setAppArgRegexAllowlist(List.of("^--date$", "^\\d{4}-\\d{2}-\\d{2}$"));
    SparkSubmitTaskExecutor restricted = new SparkSubmitTaskExecutor(props);

    TaskResult r = restricted.execute(ctx(Map.of(
        "appResource",
        "s3a://jobs/x.jar",
        "appArgs",
        List.of("--date", "; rm -rf /"),
        PipelineRuntimeKeys.DRY_RUN,
        true)));
    assertThat(r.success()).isFalse();
    assertThat(r.output()).containsEntry("error_code", "CONFIG_INVALID");
  }

  @Test
  @DisplayName("配置键不在允许前缀内时应判失败, 避免通过配置注入任意参数")
  void confKeyNotInAllowlist_failsConfigInvalid() {
    SparkSubmitExecutorProperties props = new SparkSubmitExecutorProperties();
    props.setEnabled(true);
    props.setDefaultMaster("local[*]");
    props.setAllowedConfKeyPrefixes(Set.of("spark.sql."));
    SparkSubmitTaskExecutor restricted = new SparkSubmitTaskExecutor(props);

    TaskResult r = restricted.execute(ctx(Map.of(
        "appResource",
        "s3a://jobs/x.jar",
        "sparkConf",
        Map.of("spark.driver.extraJavaOptions", "-Devil=1"),
        PipelineRuntimeKeys.DRY_RUN,
        true)));
    assertThat(r.success()).isFalse();
    assertThat(r.output()).containsEntry("error_code", "CONFIG_INVALID");
  }
}
