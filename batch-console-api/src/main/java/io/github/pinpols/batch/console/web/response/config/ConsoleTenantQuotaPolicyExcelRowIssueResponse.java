package io.github.pinpols.batch.console.web.response.config;

import java.util.List;

public record ConsoleTenantQuotaPolicyExcelRowIssueResponse(
    Integer rowNo, String rowKey, String policyCode, List<String> messages) {}
