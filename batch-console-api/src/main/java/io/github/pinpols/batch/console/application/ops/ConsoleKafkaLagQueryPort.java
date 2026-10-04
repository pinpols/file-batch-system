package io.github.pinpols.batch.console.application.ops;

import io.github.pinpols.batch.console.domain.ops.application.contract.response.ConsoleKafkaConsumerLagResponse;
import java.util.List;

/** Console 查询 Kafka consumer group 积压的基础设施端口。 */
public interface ConsoleKafkaLagQueryPort {

  List<ConsoleKafkaConsumerLagResponse> consumerGroupLags(String groupIdFilter);
}
