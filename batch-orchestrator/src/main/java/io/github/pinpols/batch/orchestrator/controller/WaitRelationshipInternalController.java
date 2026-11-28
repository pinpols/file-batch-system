package io.github.pinpols.batch.orchestrator.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import io.github.pinpols.batch.orchestrator.application.service.WaitRelationshipReadService;
import io.github.pinpols.batch.orchestrator.application.service.WaitRelationshipReadService.WaitRelationshipPage;
import io.github.pinpols.batch.orchestrator.domain.query.WaitRelationshipQuery;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/internal/ops/wait-relationships")
@RequiredArgsConstructor
public class WaitRelationshipInternalController {
    private final WaitRelationshipReadService service;

    @GetMapping
    public WaitRelationshipPage list(@Valid @ModelAttribute WaitRelationshipQuery query) {
        return service.list(query);
    }
}
