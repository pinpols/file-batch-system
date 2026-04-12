package com.example.batch.orchestrator.application.service;

import com.example.batch.orchestrator.domain.command.CompensationSubmitCommand;

public interface CompensationService {

  String submit(CompensationSubmitCommand command);
}
