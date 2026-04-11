package com.example.batch.trigger.infrastructure;

import com.example.batch.trigger.domain.TriggerRegistrationService;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TriggerRegistrationStartup implements ApplicationRunner {

    private final TriggerRegistrationService triggerRegistrationService;

    @Override
    public void run(ApplicationArguments args) {
        triggerRegistrationService.registerAll();
    }
}
