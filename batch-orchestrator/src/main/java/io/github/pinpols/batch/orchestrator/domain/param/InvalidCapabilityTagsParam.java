package io.github.pinpols.batch.orchestrator.domain.param;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class InvalidCapabilityTagsParam {
  private String tenantId;
  private String workerCode;
  private String rawValue;
}
