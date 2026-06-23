package io.github.pinpols.batch.worker.atomic.spark;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code spark-submit} 原子执行器配置（{@code batch.worker.executors.spark-submit}）。
 *
 * <p>骨架默认 {@link #enabled}=false → executor 不注册,SPI registry 找不到 {@code "spark_submit"} type。
 * 启用前请按真实集群补:{@link #sparkSubmitBin} 路径、{@link #defaultMaster}(yarn / k8s://… / spark://…)、 以及
 * {@link #appResourceAllowlist} / {@link #allowedConfKeyPrefixes} 这两道安全白名单。
 *
 * <p>定位:本执行器只「提交并跟踪」一个 Spark 作业(spark-submit 子进程 / client 模式),**不管理 Spark 集群** (资源/扩缩容是外部基础设施,见
 * ADR-027)。cluster 模式的 driver 在远端,取消 / 状态轮询需另接(见执行器限制说明)。
 */
@Data
@ConfigurationProperties(prefix = "batch.worker.executors.spark-submit")
public class SparkSubmitExecutorProperties {

  /** 总开关,默认 false(executor 不注册)。 */
  private boolean enabled = false;

  /** SPI taskType,job_definition.parameters 用它路由到本执行器。 */
  private String taskType = "spark_submit";

  /** spark-submit 可执行文件路径(默认走 PATH;生产建议填绝对路径)。 */
  private String sparkSubmitBin = "spark-submit";

  /** 默认 --master(参数未指定时用);为空则要求参数必须给 master。 */
  private String defaultMaster = "";

  /** 默认 --deploy-mode(client / cluster)。client 模式 driver 在本子进程,可取消;cluster 模式见执行器限制说明。 */
  private String defaultDeployMode = "client";

  /** 用户未配 timeoutSeconds 时的回退超时。 */
  private Duration defaultTimeout = Duration.ofMinutes(30);

  /** stdout / stderr 截断上限(防日志爆内存)。 */
  private int maxStdoutBytes = 1024 * 1024;

  private int maxStderrBytes = 256 * 1024;

  /**
   * appResource(jar / .py)允许的前缀白名单(如 {@code s3a://batch-jobs/}、{@code /opt/spark-apps/})。 为空 =
   * 不校验(仅限本地/联调;生产务必收紧,防任意 jar 提交执行)。
   */
  private List<String> appResourceAllowlist = List.of();

  /**
   * 允许的 {@code --conf} key 前缀白名单(如 {@code spark.}、{@code spark.sql.})。为空 = 允许全部 (仅限本地;生产建议收紧,防注入危险
   * conf,如 {@code spark.driver.extraJavaOptions} 任意 JVM 参数)。
   */
  private Set<String> allowedConfKeyPrefixes = Set.of();

  /** app 参数个数上限(防滥用)。 */
  private int maxAppArgs = 128;

  /**
   * #1 安全:spark-submit 子进程**默认只继承必要 env**(SPARK_HOME/JAVA_HOME/HADOOP_CONF_DIR 等内置必需集), 其余一律清掉,防
   * worker 环境里的 DB 密码 / 内部密钥 / 云凭据被透传进 Spark 作业泄密。本表是**额外**放行的 env key。
   */
  private Set<String> allowedEnvKeys = Set.of();

  /**
   * #4 安全:允许的 {@code --master} 前缀白名单(如 {@code yarn}、{@code k8s://}、{@code spark://prod-})。 为空 =
   * 不校验(仅本地;生产建议收紧,防用户 param 把作业提交到任意/攻击者集群或 {@code local[超大]} 耗尽本机资源)。
   */
  private List<String> allowedMasterPrefixes = List.of();

  /**
   * #7 防御:app 参数(传给 Spark app)的正则白名单,每个 appArg 必须匹配其一。为空 = 不校验。 注:appArgs 在 appResource 之后只传给
   * app(不会被 spark-submit 解释),且走 execve 无 shell 注入,故默认宽松。
   */
  private List<String> appArgRegexAllowlist = List.of();

  /**
   * 给了 {@code outputPath} 参数时,以该 conf key 注入给 Spark app 读取落盘目录(契约: app 用 {@code sparkConf.get(此
   * key)} 拿输出路径)。成功时执行器把 outputPath 回写到 {@code TaskResult.output["outputUri"]},下游(EXPORT / console
   * 下载 / 下一节点)按它取加工后 CSV。
   */
  private String outputPathConfKey = "spark.batch.outputPath";

  /**
   * 是否在 exit=0 后校验本地输出目录有 {@code _SUCCESS} 标记(防 Spark 退 0 但没真写出)。默认 false。 仅对本地 FS 路径生效;远端({@code
   * s3a:// / hdfs://})执行器够不着,需下游消费步骤自行核验。
   */
  private boolean verifyLocalSuccessMarker = false;
}
