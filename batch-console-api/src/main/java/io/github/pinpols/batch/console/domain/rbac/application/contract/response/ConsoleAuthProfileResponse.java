package io.github.pinpols.batch.console.domain.rbac.application.contract.response;

import java.util.List;
import java.util.Set;

import io.github.pinpols.batch.console.domain.rbac.support.ConsoleMenuRegistry.MenuGroup;

public record ConsoleAuthProfileResponse(
        String username,
        String tenantId,
        Set<String> authorities,
        List<MenuGroup> menus,
        Set<String> capabilities,
        boolean mustChangePassword) {}
