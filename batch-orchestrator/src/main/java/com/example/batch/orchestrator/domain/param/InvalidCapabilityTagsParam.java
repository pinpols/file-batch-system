package com.example.batch.orchestrator.domain.param;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class InvalidCapabilityTagsRecord {
  private String tenantId;
  private String workerCode;
  private String rawValue;
}
