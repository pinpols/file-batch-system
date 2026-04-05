package com.example.batch.orchestrator.controller;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.orchestrator.application.service.LaunchApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/orchestrator")
@RequiredArgsConstructor
public class LaunchController {

    private final LaunchApplicationService launchApplicationService;

    @PostMapping("/launch")
    public LaunchResponse launch(@RequestBody LaunchRequest request) {
        return launchApplicationService.launch(request);
    }
}
