package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.pinpols.batch.common.kafka.BatchKafkaTopicNamespace;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.worker.core.support.AbstractTaskConsumer;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.GroupState;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 通过真实 broker 核验独立 Worker 应用的消费组 topic 分配。 */
@E2eBusinessSchema
@DisplayName("Worker Kafka 部署命名空间隔离端到端基类")
abstract class AbstractWorkerKafkaNamespaceIsolationE2eIT extends AbstractIntegrationTest {

  private static final String NAMESPACE =
      "r24-worker-kafka-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  private static final Duration GROUP_ASSIGNMENT_TIMEOUT = Duration.ofSeconds(45);
  private static final int TOPIC_PARTITIONS = 1;
  private static final short TOPIC_REPLICATION_FACTOR = 1;

  @Autowired
  private List<AbstractTaskConsumer> taskConsumers;

  @Autowired
  private BatchKafkaTopicNamespace topicNamespace;

  @Autowired
  private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

  @DynamicPropertySource
  static void registerDeploymentNamespace(DynamicPropertyRegistry registry) {
    registry.add("batch.kafka.namespace", () -> NAMESPACE);
  }

  protected abstract String legacyTopic();

  @Test
  @DisplayName("独立 Worker 消费组只分配新 namespace topic")
  void shouldAssignOnlyNamespacedTopic_whenLegacyTopicAlsoExists() throws Exception {
    assertThat(taskConsumers)
        .as("one independently launched Worker application should expose one task consumer")
        .hasSize(1);

    AbstractTaskConsumer taskConsumer = taskConsumers.getFirst();
    String namespacedTopic = topicNamespace.topic(legacyTopic());
    assertThat(taskConsumer.topics()).contains(namespacedTopic);

    ConsumerProbe probe =
        new ConsumerProbe(taskConsumer.consumerGroupId(), namespacedTopic, legacyTopic());
    Set<String> requestedTopics = Set.of(probe.namespacedTopic(), probe.legacyTopic());
    Set<String> testOwnedTopics = new LinkedHashSet<>(Set.of(probe.namespacedTopic()));

    try (Admin admin = kafkaAdmin()) {
      try {
        createMissingTopics(admin, requestedTopics, testOwnedTopics);
        await()
            .atMost(GROUP_ASSIGNMENT_TIMEOUT)
            .pollInterval(Duration.ofMillis(250))
            .untilAsserted(() -> assertNamespacedAssignment(admin, probe));
      } finally {
        stopKafkaListeners();
        deleteTopics(admin, testOwnedTopics);
      }
    }
  }

  @AfterEach
  void stopListenersAfterEveryScenario() {
    stopKafkaListeners();
  }

  private static Admin kafkaAdmin() {
    return Admin.create(Map.of(
        AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers()));
  }

  private static void createMissingTopics(
      Admin admin, Set<String> requestedTopics, Set<String> testOwnedTopics) throws Exception {
    Set<String> existingTopics = admin.listTopics().names().get(10, TimeUnit.SECONDS);
    List<String> missingTopics = requestedTopics.stream()
        .filter(topic -> !existingTopics.contains(topic))
        .toList();
    for (String topic : missingTopics) {
      try {
        admin.createTopics(List.of(new NewTopic(
                topic, TOPIC_PARTITIONS, TOPIC_REPLICATION_FACTOR)))
            .all()
            .get(10, TimeUnit.SECONDS);
        if (testOwnedTopics.contains(topic)) {
          continue;
        }
      } catch (ExecutionException exception) {
        if (!(exception.getCause() instanceof TopicExistsException)) {
          throw exception;
        }
      }
    }
  }

  private static void assertNamespacedAssignment(Admin admin, ConsumerProbe probe)
      throws Exception {
    ConsumerGroupDescription description;
    try {
      description = admin.describeConsumerGroups(List.of(probe.groupId()))
          .all()
          .get(10, TimeUnit.SECONDS)
          .get(probe.groupId());
    } catch (ExecutionException exception) {
      if (exception.getCause() instanceof GroupIdNotFoundException) {
        throw new AssertionError("Namespaced Worker consumer group has not joined yet", exception);
      }
      throw exception;
    }

    assertThat(description)
        .as("consumer group %s should be active", probe.groupId())
        .isNotNull();
    assertThat(description.groupState()).isEqualTo(GroupState.STABLE);
    assertThat(description.members()).isNotEmpty();
    assertThat(assignedTopics(description.members()))
        .as("consumer group %s topic assignment", probe.groupId())
        .contains(probe.namespacedTopic())
        .doesNotContain(probe.legacyTopic());
  }

  private static Set<String> assignedTopics(Iterable<MemberDescription> members) {
    Set<String> topics = new LinkedHashSet<>();
    for (MemberDescription member : members) {
      for (TopicPartition partition : member.assignment().topicPartitions()) {
        topics.add(partition.topic());
      }
    }
    return topics;
  }

  private void stopKafkaListeners() {
    for (MessageListenerContainer container : kafkaListenerEndpointRegistry.getListenerContainers()) {
      container.stop();
    }
  }

  private static void deleteTopics(Admin admin, Set<String> topics) throws Exception {
    if (!topics.isEmpty()) {
      admin.deleteTopics(topics).all().get(10, TimeUnit.SECONDS);
    }
  }

  private record ConsumerProbe(String groupId, String namespacedTopic, String legacyTopic) {}
}
