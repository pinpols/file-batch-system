package io.github.pinpols.batch.orchestrator.infrastructure.mq;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.kafka.TaskDispatchMessage;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.config.BatchMqTopicsProperties;
import io.github.pinpols.batch.orchestrator.config.MqRoutingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("消息主题解析器,验证单主题,按租户与按优先级三种路由模式下的主题拼接,缺省回退与非法字符清洗")
class BatchTopicResolverTest {

  private BatchMqTopicsProperties topics;
  private MqRoutingProperties routing;
  private BatchTopicResolver resolver;

  @BeforeEach
  void setUp() {
    topics = new BatchMqTopicsProperties();
    routing = new MqRoutingProperties();
    resolver = new BatchTopicResolver(topics, routing);
  }

  @Test
  @DisplayName("无法识别的执行节点类型不解析出任何主题,返回空值而不是抛出异常")
  void shouldReturnNull_whenWorkerTypeUnsupported() {
    assertThat(resolver.resolve("UNKNOWN", null)).isNull();
  }

  @Test
  @DisplayName("单主题路由模式下消息落到基础主题,不追加任何租户或优先级后缀")
  void shouldReturnBaseTopic_whenSingleMode() {
    routing.setMode(MqRoutingProperties.Mode.SINGLE);
    String topic = resolver.resolve("IMPORT", message("t1", "HIGH"));
    assertThat(topic).isEqualTo(topics.getImportDispatch());
  }

  @Test
  @DisplayName("按租户路由模式下在基础主题后追加租户后缀,使不同租户的派发消息彼此隔离")
  void shouldAppendTenantSuffix_whenTenantMode() {
    routing.setMode(MqRoutingProperties.Mode.TENANT);
    String topic = resolver.resolve("EXPORT", message("ta", "NORMAL"));
    assertThat(topic).isEqualTo(topics.getExportDispatch() + ".ta");
  }

  @Test
  @DisplayName("按租户路由模式但消息缺少租户标识时回退到基础主题,不追加空后缀")
  void shouldFallBackToBaseTopic_whenTenantMissing() {
    routing.setMode(MqRoutingProperties.Mode.TENANT);
    String topic = resolver.resolve("EXPORT", message(null, "NORMAL"));
    assertThat(topic).isEqualTo(topics.getExportDispatch());
  }

  @Test
  @DisplayName("按优先级路由模式下追加小写的优先级后缀,使高优消息进入独立主题")
  void shouldAppendLowercasePrioritySuffix_whenPriorityMode() {
    routing.setMode(MqRoutingProperties.Mode.PRIORITY);
    String topic = resolver.resolve("DISPATCH", message("t1", "HIGH"));
    assertThat(topic).isEqualTo(topics.getDispatchDispatch() + ".high");
  }

  @Test
  @DisplayName("按优先级路由模式但消息缺少优先级时回退到基础主题,不追加后缀")
  void shouldFallBackToBaseTopic_whenPriorityBandMissing() {
    routing.setMode(MqRoutingProperties.Mode.PRIORITY);
    String topic = resolver.resolve("DISPATCH", message("t1", null));
    assertThat(topic).isEqualTo(topics.getDispatchDispatch());
  }

  @Test
  @DisplayName("租户标识含非法字符时替换为下划线再拼接后缀,保证最终主题名合法")
  void shouldReplaceIllegalTenantChars_whenBuildingTenantTopic() {
    routing.setMode(MqRoutingProperties.Mode.TENANT);
    String topic = resolver.resolve("IMPORT", message("ta:1$bad", "HIGH"));
    assertThat(topic).endsWith(".ta_1_bad");
  }

  private static TaskDispatchMessage message(String tenantId, String priorityBand) {
    return new TaskDispatchMessage(
        "v2",
        tenantId,
        1L,
        2L,
        3L,
        "INST-1",
        "JOB",
        "IMPORT",
        null,
        priorityBand,
        "trace-1",
        "idem-1",
        BatchDateTimeSupport.utcNow(),
        null);
  }
}
