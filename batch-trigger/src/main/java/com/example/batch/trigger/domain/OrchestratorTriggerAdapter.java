package com.example.batch.trigger.domain;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;

public interface OrchestratorTriggerAdapter {

  LaunchResponse sendTrigger(LaunchRequest request);
}
