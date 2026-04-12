package com.example.batch.orchestrator.domain.pipeline;

import java.util.Set;
import lombok.Data;

@Data
public class StepDefinition {

  private String stepCode;
  private String stepName;
  private String stepType;
  private Integer stepOrder;
  private String workerType;
  private Set<String> capabilityTags;
  private String resourceProfile;
  private Boolean enabled;
}
