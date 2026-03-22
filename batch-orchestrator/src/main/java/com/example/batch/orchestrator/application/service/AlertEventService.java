package com.example.batch.orchestrator.application.service;

import com.example.batch.orchestrator.domain.dto.AlertEmitRequest;

public interface AlertEventService {

    void emit(AlertEmitRequest request);
}
