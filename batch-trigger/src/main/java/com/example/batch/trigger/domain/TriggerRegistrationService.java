package com.example.batch.trigger.domain;

public interface TriggerRegistrationService {

    void registerAll();

    void registerByJobCode(String tenantId, String jobCode);
}
