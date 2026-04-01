package com.example.batch.trigger.domain;

public interface TriggerRegistrationService {

    void registerAll();

    void registerByJobCode(String tenantId, String jobCode);

    void unregisterByJobCode(String tenantId, String jobCode);

    void pauseByJobCode(String tenantId, String jobCode);

    void resumeByJobCode(String tenantId, String jobCode);

    java.util.List<TriggerStatusInfo> listRegisteredTriggers();

    void pauseAll();

    void resumeAll();

    String schedulerStatus();
}
