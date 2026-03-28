package com.example.batch.console.application;

import com.example.batch.console.web.response.ConsoleOpsSummaryResponse;

public interface ConsoleOpsApplicationService {

    ConsoleOpsSummaryResponse summary(String tenantId);
}
