package com.example.batch.orchestrator.application.service;

import com.example.batch.orchestrator.controller.request.AlertEmitRequest;

public interface AlertEventService {

    void emit(AlertEmitRequest request);
}
