package io.github.pinpols.batch.worker.core.support;

import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.kafka.TaskDispatchMessage;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.worker.core.config.WorkerConfiguration;
import io.github.pinpols.batch.worker.core.config.WorkerKafkaSubscribeProperties;
import io.github.pinpols.batch.worker.core.domain.WorkerRegistration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * 负责 worker 消费端的 topic 路由和基础消息筛选。
 *
 * <p>producer 可以按单 topic、租户后缀、优先级后缀或指定 worker 输出消息；消费者必须使用同一套规则构造
 * 订阅 pattern，并先过滤 worker 类型和指定 worker，避免无关消息占用执行容量。租户范围仍由消费者子类保留
 * 最终裁决权，因为 Import 等 worker 允许按业务配置放开跨租户消费。
 */
final class TaskConsumerRoutingPolicy {

  private static final Map<String, String> WORKER_TYPE_TOPIC = Map.of(
      "IMPORT", BatchTopics.TASK_DISPATCH_IMPORT,
      "EXPORT", BatchTopics.TASK_DISPATCH_EXPORT,
      "PROCESS", BatchTopics.TASK_DISPATCH_PROCESS,
      "DISPATCH", BatchTopics.TASK_DISPATCH_DISPATCH,
      "ATOMIC", BatchTopics.TASK_DISPATCH_ATOMIC);

  private static final Map<String, String> WORKER_CODE_KEYWORD_TOPIC =
      buildWorkerCodeKeywordTopic();

  private TaskConsumerRoutingPolicy() {}

  static String[] topics(WorkerConfiguration configuration) {
    String configuredWorkerCode = configuration.workerCode();
    String baseTopic = resolveBaseTopic(configuration);
    if (EmptyChecks.isBlank(configuredWorkerCode)) {
      return new String[] {baseTopic};
    }
    return new String[] {baseTopic, BatchTopics.directDispatchTopic(baseTopic, configuredWorkerCode)
    };
  }

  static String topicPattern(
      WorkerConfiguration configuration, WorkerKafkaSubscribeProperties properties) {
    String baseTopic = resolveBaseTopic(configuration);
    String safeBase = baseTopic.replace(".", "\\.");
    String configuredWorkerCode = configuration.workerCode();
    String nodeDirect = EmptyChecks.isBlank(configuredWorkerCode)
        ? null
        : "\\.node\\." + escapeRegex(configuredWorkerCode);

    WorkerKafkaSubscribeProperties.Mode mode = properties == null
        ? WorkerKafkaSubscribeProperties.Mode.PATTERN
        : properties.getSubscribeMode();
    String suffixAlt;
    switch (mode) {
      case FIXED:
        suffixAlt = nodeDirect;
        break;
      case TENANT_SCOPED:
        List<String> allow = properties == null || properties.getTenantAllowlist() == null
            ? List.of()
            : properties.getTenantAllowlist();
        String tenantAlt = allow.stream()
            .filter(EmptyChecks::isNotBlank)
            .map(TaskConsumerRoutingPolicy::escapeRegex)
            .reduce((left, right) -> left + "|" + right)
            .orElse(null);
        suffixAlt = joinAlt(nodeDirect, tenantAlt == null ? null : "\\.(" + tenantAlt + ")");
        break;
      case PATTERN:
      default:
        suffixAlt = joinAlt(nodeDirect, "\\.[^.]+");
    }
    if (suffixAlt == null) {
      return "^" + safeBase + "$";
    }
    return "^" + safeBase + "(" + suffixAlt + ")?$";
  }

  static boolean accepts(
      TaskDispatchMessage message,
      WorkerRegistration registration,
      WorkerConfiguration configuration,
      BiPredicate<WorkerConfiguration, TaskDispatchMessage> tenantScope) {
    if (message == null
        || message.taskId() == null
        || message.tenantId() == null
        || message.workerType() == null) {
      return false;
    }
    if (configuration.workerType() == null
        || !configuration.workerType().equalsIgnoreCase(message.workerType())) {
      return false;
    }
    if (message.selectedWorkerId() != null
        && (registration == null
            || !message.selectedWorkerId().equals(registration.getWorkerId()))) {
      return false;
    }
    return tenantScope.test(configuration, message);
  }

  private static String resolveBaseTopic(WorkerConfiguration configuration) {
    String configuredWorkerCode = configuration.workerCode();
    String baseTopic = configuration.topic();
    if (EmptyChecks.isBlank(baseTopic)) {
      baseTopic = resolveTopicByWorkerType(configuration.workerType());
      if (EmptyChecks.isBlank(baseTopic)) {
        baseTopic = resolveTopicByWorkerCode(configuredWorkerCode);
      }
    }
    return EmptyChecks.isBlank(baseTopic) ? BatchTopics.TASK_DISPATCH_DISPATCH : baseTopic;
  }

  private static String resolveTopicByWorkerType(String workerType) {
    return EmptyChecks.isBlank(workerType)
        ? null
        : WORKER_TYPE_TOPIC.get(workerType.toUpperCase(Locale.ROOT));
  }

  private static String resolveTopicByWorkerCode(String workerCode) {
    String normalized = workerCode == null ? "" : workerCode.toLowerCase(Locale.ROOT);
    return WORKER_CODE_KEYWORD_TOPIC.entrySet().stream()
        .filter(entry -> normalized.contains(entry.getKey()))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(null);
  }

  private static Map<String, String> buildWorkerCodeKeywordTopic() {
    LinkedHashMap<String, String> map = new LinkedHashMap<>(4);
    map.put("import", BatchTopics.TASK_DISPATCH_IMPORT);
    map.put("export", BatchTopics.TASK_DISPATCH_EXPORT);
    map.put("process", BatchTopics.TASK_DISPATCH_PROCESS);
    map.put("dispatch", BatchTopics.TASK_DISPATCH_DISPATCH);
    map.put("atomic", BatchTopics.TASK_DISPATCH_ATOMIC);
    return Collections.unmodifiableMap(map);
  }

  private static String joinAlt(String left, String right) {
    if (left == null) {
      return right;
    }
    if (right == null) {
      return left;
    }
    return left + "|" + right;
  }

  private static String escapeRegex(String value) {
    return value.replaceAll("([\\\\\\.\\[\\]\\(\\)\\{\\}\\^\\$\\|\\?\\*\\+])", "\\\\$1");
  }
}
