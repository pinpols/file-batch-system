package io.github.pinpols.batch.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.dto.LaunchEnvelope;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 守护:Kafka 跨服务事件 payload 的 wire schema 不被静默破坏。
 *
 * <p>这些 DTO 经 JSON 序列化跨 trigger/orchestrator/worker 流转(batch.trigger.launch.v1 / task.dispatch /
 * task.retry / outbox.event / dead-letter)。删字段 / 改名 / 改类型 = 破坏旧消费者反序列化(消息在途/积压时尤其致命), 但 e2e
 * 未必覆盖跨版本场景。本测试把字段签名快照进 golden 资源,任何变更即 fail,逼迫显式 review:
 *
 * <ul>
 *   <li>纯新增字段(向后兼容)→ 重新生成 golden 即可(见失败提示)。
 *   <li>删 / 改名 / 改类型(破坏)→ 必须改走版本化 topic(如 .v2)+ 双写迁移,禁直接改。
 * </ul>
 */
class KafkaEventSchemaGuardTest {

  /** 受守护的跨服务 wire 契约类(新增 Kafka payload 类型时在此登记)。 */
  private static final List<Class<?>> WIRE_CONTRACTS =
      List.of(
          BatchEventMessage.class,
          BatchRetryMessage.class,
          TaskDispatchMessage.class,
          BatchDeadLetterMessage.class,
          SchedulingContext.class,
          LaunchEnvelope.class);

  private static final String GOLDEN = "/kafka-event-schema.golden";

  @Test
  void wireSchemaMatchesGolden() {
    String actual =
        WIRE_CONTRACTS.stream()
                .map(KafkaEventSchemaGuardTest::signature)
                .collect(Collectors.joining("\n"))
            + "\n";

    String golden = readGolden();
    assertThat(actual)
        .as(
            "Kafka 事件 wire schema 变了。若为纯新增字段(向后兼容),用以下实际签名更新 "
                + "batch-common/src/test/resources/kafka-event-schema.golden;"
                + "若删/改名/改类型(破坏消费者),禁直接改 —— 走版本化 topic(.v2)+ 双写迁移。\n实际签名:\n"
                + actual)
        .isEqualTo(golden);
  }

  /** 生成 {@code FQN{field:Type,...}} 签名;record 用 components,普通类用 declared fields,按字段名排序。 */
  private static String signature(Class<?> type) {
    List<String> fields = new ArrayList<>();
    if (type.isRecord()) {
      for (RecordComponent rc : type.getRecordComponents()) {
        fields.add(rc.getName() + ":" + rc.getType().getSimpleName());
      }
    } else {
      for (Field f : type.getDeclaredFields()) {
        if (f.isSynthetic() || Modifier.isStatic(f.getModifiers())) {
          continue;
        }
        fields.add(f.getName() + ":" + f.getType().getSimpleName());
      }
    }
    fields.sort(String::compareTo);
    return type.getName() + "{" + String.join(",", fields) + "}";
  }

  private static String readGolden() {
    try (InputStream in = KafkaEventSchemaGuardTest.class.getResourceAsStream(GOLDEN)) {
      assertThat(in).as("缺少 golden 资源 %s —— 首次启用请用测试失败提示里的实际签名创建它", GOLDEN).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 golden 失败", e);
    }
  }
}
