package com.example.batch.orchestrator.controller;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.orchestrator.service.LaunchService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/orchestrator")
public class LaunchController {

    private final LaunchService launchService;

    public LaunchController(LaunchService launchService) {
        this.launchService = launchService;
    }

    @PostMapping("/launch")
    public LaunchResponse launch(@RequestBody LaunchRequest request) {
        return launchService.launch(request);
    }
}
