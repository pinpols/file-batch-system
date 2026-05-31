package com.example.batch.console.domain.job.support;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import java.util.Locale;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Builtin Task SPI(Shell / SQL / StoredProc / HTTP)的使用边界守门 — 见 ADR-035 §使用边界。
 *
 * <p><b>规则</b>:builtin 4 件套**不能被非平台 ADMIN 用户**通过 jobType 引用,租户要这 4 类能力必须走 SDK 自托管 (ADR-035 /
 * 自己拷代码自己跑)。
 *
 * <p><b>为什么</b>:平台 worker 跑这 4 件套 = 所有租户共享同 1 个进程,Shell 命令能看见别人临时文件,SQL 走平台 dataSource 跨租户读取,HTTP
 * 出口 IP 跟租户白名单耦合 — 多租户隔离破洞。详 ADR-035 §使用边界 + Scenario A。
 *
 * <p><b>当前拦截层级</b>:`ConsoleJobDefinitionController` 类级
 * {@code @PreAuthorize("hasAuthority('ROLE_ADMIN')")} 已保证只有平台 ADMIN 能 POST/PUT
 * job。本守门是**第二道防御**(defense in depth),也为后续放开 TENANT_ADMIN 写权限时铺路。
 */
@Component
public class BuiltinTaskTypeGuard {

  /**
   * Builtin SPI taskType 集合(全小写,跟 P0 Phase 2 各 ExecutorProperties.taskType 默认值对齐)。永远不许出现在租户创建的
   * job_definition.job_type 字段里。
   */
  public static final Set<String> RESERVED_BUILTIN_TASK_TYPES =
      Set.of("shell", "sql", "stored_proc", "http");

  private static final String PLATFORM_ADMIN_AUTHORITY = "ROLE_ADMIN";

  /**
   * 校验 jobType 是否允许当前用户创建 / 更新。违规抛 {@link BizException} ({@link ResultCode#FORBIDDEN})。
   *
   * @param jobType 用户提交的 jobType 字段(可空 / 任意大小写)
   */
  public void assertAllowed(String jobType) {
    if (jobType == null) {
      return;
    }
    String normalized = jobType.trim().toLowerCase(Locale.ROOT);
    if (!RESERVED_BUILTIN_TASK_TYPES.contains(normalized)) {
      return;
    }
    if (currentUserIsPlatformAdmin()) {
      return;
    }
    throw BizException.of(
        ResultCode.FORBIDDEN,
        "error.job.builtin_task_type_reserved",
        normalized,
        String.join(", ", RESERVED_BUILTIN_TASK_TYPES));
  }

  private boolean currentUserIsPlatformAdmin() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated()) {
      return false;
    }
    return auth.getAuthorities().stream()
        .anyMatch(g -> PLATFORM_ADMIN_AUTHORITY.equals(g.getAuthority()));
  }
}
