package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.pinpols.batch.common.kafka.BatchKafkaTopicNamespace;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.console.domain.notification.infrastructure.JobTerminalNotificationConsumer;
import io.github.pinpols.batch.console.domain.notification.infrastructure.WorkflowTerminalNotificationConsumer;
import io.github.pinpols.batch.e2e.apps.E2eConsoleImportApplication;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.orchestrator.infrastructure.lineage.WorkflowTerminalLineageConsumer;
import io.github.pinpols.batch.orchestrator.infrastructure.trigger.TriggerLaunchConsumer;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.worker.imports.runtime.ImportTaskConsumer;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@E2eSpringBootTest(
    classes = E2eConsoleImportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.kafka.listener.auto-startup=true",
      "batch.startup-self-check.enabled=false",
      "batch.openlineage.enabled=true"
    })
@E2eBusinessSchema
@Tag("critical")
@DisplayName("Kafka 部署命名空间隔离:新实例的消费者只分配新 topic")
class KafkaConsumerNamespaceIsolationE2eIT extends AbstractIntegrationTest {

  private static final String NAMESPACE =
      "r24-kafka-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  private static final Duration GROUP_ASSIGNMENT_TIMEOUT = Duration.ofSeconds(45);
  private static final int TOPIC_PARTITIONS = 1;
  private static final short TOPIC_REPLICATION_FACTOR = 1;

  @Autowired
  private BatchKafkaTopicNamespace topicNamespace;

  @Autowired
  private TriggerLaunchConsumer triggerLaunchConsumer;

  @Autowired
  private WorkflowTerminalLineageConsumer workflowTerminalLineageConsumer;

  @Autowired
  private WorkflowTerminalNotificationConsumer workflowTerminalNotificationConsumer;

  @Autowired
  private JobTerminalNotificationConsumer jobTerminalNotificationConsumer;

  @Autowired
  private ImportTaskConsumer importTaskConsumer;

  @Autowired
  private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

  @DynamicPropertySource
  static void registerDeploymentNamespace(DynamicPropertyRegistry registry) {
    registry.add("batch.kafka.namespace", () -> NAMESPACE);
  }

  @Test
  @DisplayName("Trigger、终态通知、血缘和 Import Worker 消费组均不订阅未命名空间 topic")
  void shouldAssignOnlyNamespacedTopics_whenLegacyTopicsAlsoExist() throws Exception {
    List<ConsumerProbe> probes = consumerProbes();
    Set<String> topics = topicNames(probes);
    Set<String> testOwnedTopics = new LinkedHashSet<>(namespacedTopics(probes));

    try (Admin admin = kafkaAdmin()) {
      try {
        createMissingTopics(admin, topics, testOwnedTopics);
        await()
            .atMost(GROUP_ASSIGNMENT_TIMEOUT)
            .pollInterval(Duration.ofMillis(250))
            .untilAsserted(() -> assertNamespacedAssignments(admin, probes));
      } finally {
        stopKafkaListeners();
        deleteTopics(admin, testOwnedTopics);
      }
    }
  }

  private List<ConsumerProbe> consumerProbes() {
    return List.of(
        new ConsumerProbe(
            triggerLaunchConsumer.consumerGroupId(),
            triggerLaunchConsumer.topicName(),
            BatchTopics.TRIGGER_LAUNCH_V1),
        new ConsumerProbe(
            workflowTerminalLineageConsumer.consumerGroupId(),
            workflowTerminalLineageConsumer.topicName(),
            BatchTopics.WORKFLOW_TERMINAL_V1),
        new ConsumerProbe(
            workflowTerminalNotificationConsumer.consumerGroupId(),
            workflowTerminalNotificationConsumer.topicName(),
            BatchTopics.WORKFLOW_TERMINAL_V1),
        new ConsumerProbe(
            jobTerminalNotificationConsumer.consumerGroupId(),
            jobTerminalNotificationConsumer.topicName(),
            BatchTopics.JOB_TERMINAL_V1),
        new ConsumerProbe(
            importTaskConsumer.consumerGroupId(),
            topicNamespace.topic(BatchTopics.TASK_DISPATCH_IMPORT),
            BatchTopics.TASK_DISPATCH_IMPORT));
  }

  private static Set<String> topicNames(List<ConsumerProbe> probes) {
    Set<String> names = new LinkedHashSet<>();
    for (ConsumerProbe probe : probes) {
      names.add(probe.namespacedTopic());
      names.add(probe.legacyTopic());
    }
    return names;
  }

  private static Set<String> namespacedTopics(List<ConsumerProbe> probes) {
    Set<String> names = new LinkedHashSet<>();
    for (ConsumerProbe probe : probes) {
      names.add(probe.namespacedTopic());
    }
    return names;
  }

  private void createMissingTopics(
      Admin admin, Set<String> requestedTopics, Set<String> testOwnedTopics) throws Exception {
    Set<String> existingTopics = admin.listTopics().names().get(10, TimeUnit.SECONDS);
    List<String> missingTopics = requestedTopics.stream()
        .filter(topic -> !existingTopics.contains(topic))
        .toList();
    for (String topic : missingTopics) {
      try {
        admin
            .createTopics(List.of(new NewTopic(topic, TOPIC_PARTITIONS, TOPIC_REPLICATION_FACTOR)))
            .all()
            .get(10, TimeUnit.SECONDS);
        testOwnedTopics.add(topic);
      } catch (ExecutionException exception) {
        if (!(exception.getCause() instanceof TopicExistsException)) {
          throw exception;
        }
      }
    }
  }

  private static Admin kafkaAdmin() {
    return Admin.create(
        Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers()));
  }

  private static void assertNamespacedAssignments(Admin admin, List<ConsumerProbe> probes)
      throws Exception {
    List<String> groupIds =
        probes.stream().map(ConsumerProbe::groupId).distinct().toList();
    Map<String, ConsumerGroupDescription> descriptions;
    try {
      descriptions = admin.describeConsumerGroups(groupIds).all().get(10, TimeUnit.SECONDS);
    } catch (ExecutionException exception) {
      if (exception.getCause() instanceof GroupIdNotFoundException) {
        throw new AssertionError("Namespaced Kafka consumer groups have not joined yet", exception);
      }
      throw exception;
    }

    for (ConsumerProbe probe : probes) {
      ConsumerGroupDescription description = descriptions.get(probe.groupId());
      assertThat(description)
          .as("consumer group %s should be active", probe.groupId())
          .isNotNull();
      assertThat(description.groupState()).isEqualTo(GroupState.STABLE);
      assertThat(description.members()).isNotEmpty();

      Set<String> assignedTopics = assignedTopics(description.members());
      assertThat(assignedTopics)
          .as("consumer group %s topic assignment", probe.groupId())
          .contains(probe.namespacedTopic())
          .doesNotContain(probe.legacyTopic());
    }
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
    for (MessageListenerContainer container :
        kafkaListenerEndpointRegistry.getListenerContainers()) {
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
