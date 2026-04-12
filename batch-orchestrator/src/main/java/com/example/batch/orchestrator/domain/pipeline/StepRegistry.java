package com.example.batch.orchestrator.domain.pipeline;

import java.util.Optional;

public interface StepRegistry {

  Optional<Step> find(String stepCode);
}
