package com.example.batch.console.web.response;

import java.util.List;

public record ConsoleTenantQuotaPolicyExcelRowIssueResponse(
        Integer rowNo, String rowKey, String policyCode, List<String> messages) {}
