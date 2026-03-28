package com.example.batch.console.web.response;

import java.util.Set;

public record ConsoleAuthProfileResponse(
        String username,
        String tenantId,
        Set<String> authorities
) {
}
