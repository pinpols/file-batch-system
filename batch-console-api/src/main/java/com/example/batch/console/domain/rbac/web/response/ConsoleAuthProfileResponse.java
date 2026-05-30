package com.example.batch.console.domain.rbac.web.response;

import com.example.batch.console.domain.rbac.support.ConsoleMenuRegistry.MenuGroup;
import java.util.List;
import java.util.Set;

public record ConsoleAuthProfileResponse(
    String username, String tenantId, Set<String> authorities, List<MenuGroup> menus) {}
