package io.github.pinpols.batch.worker.imports.infrastructure.quality;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ValidationIssueMaskerTest {

  @Test
  void shouldTreatNullIssueCollectionsAsEmptyWhenMaskingIsDisabled() {
    ValidationIssueMasker masker = new ValidationIssueMasker(new BatchSecurityProperties());
    ValidationSession session = new ValidationSession(
        null, Map.of(), 0L, null, null, List.of(), null, null, null, Set.of());

    ValidationOutcome outcome = masker.maskOutcome(session);

    assertThat(outcome.recordIssues()).isEmpty();
    assertThat(outcome.datasetIssues()).isEmpty();
  }
}
