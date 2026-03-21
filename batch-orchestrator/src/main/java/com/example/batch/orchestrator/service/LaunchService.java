package com.example.batch.orchestrator.service;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;

public interface LaunchService {

    LaunchResponse launch(LaunchRequest request);
}
