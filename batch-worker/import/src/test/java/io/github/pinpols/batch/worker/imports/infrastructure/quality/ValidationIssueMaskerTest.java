package io.github.pinpols.batch.worker.imports.infrastructure.quality;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("校验问题掩码单测:未开启掩码时对空问题集合的兜底处理")
class ValidationIssueMaskerTest {

  @Test
  @DisplayName("未开启掩码且问题集合为空时,输出的记录级与数据集级问题都为空")
  void shouldTreatNullIssueCollectionsAsEmptyWhenMaskingIsDisabled() {
    ValidationIssueMasker masker = new ValidationIssueMasker(new BatchSecurityProperties());
    ValidationSession session = new ValidationSession(
        null, Map.of(), 0L, null, null, List.of(), null, null, null, Set.of());

    ValidationOutcome outcome = masker.maskOutcome(session);

    assertThat(outcome.recordIssues()).isEmpty();
    assertThat(outcome.datasetIssues()).isEmpty();
  }
}
