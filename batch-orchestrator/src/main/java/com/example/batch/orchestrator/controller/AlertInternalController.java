package com.example.batch.orchestrator.controller;

import com.example.batch.orchestrator.application.service.AlertEventService;
import com.example.batch.orchestrator.domain.dto.AlertEmitRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/alerts")
@RequiredArgsConstructor
public class AlertInternalController {

    private final AlertEventService alertEventService;

    @PostMapping
    public void emit(@RequestBody AlertEmitRequest request) {
        alertEventService.emit(request);
    }
}
