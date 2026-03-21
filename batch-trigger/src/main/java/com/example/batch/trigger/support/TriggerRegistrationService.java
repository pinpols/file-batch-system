package com.example.batch.trigger.support;

public interface TriggerRegistrationService {

    void registerAll();

    void registerByJobCode(String tenantId, String jobCode);
}
