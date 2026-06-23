package io.github.pinpols.batch.common.constants;

import java.util.List;
import java.util.Set;

public final class CommonConstants {

  public static final String DEFAULT_TENANT_ID = "default-tenant";
  public static final String SYSTEM_TENANT_ID = "system";
  public static final String DEFAULT_TEMPLATE_TENANT_ID = "default";
  public static final String ADMIN_TENANT_ID = "admin";
  public static final String DEFAULT_TIMEZONE_ID = "Asia/Shanghai";
  public static final String DEFAULT_TRACE_ID_HEADER = "X-Trace-Id";
  public static final String DEFAULT_REQUEST_ID_HEADER = "X-Request-Id";
  public static final String DEFAULT_TENANT_ID_HEADER = "X-Tenant-Id";
  public static final String DEFAULT_OPERATOR_ID_HEADER = "X-Operator-Id";
  public static final String DEFAULT_IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
  public static final String DEFAULT_FORWARDED_FOR_HEADER = "X-Forwarded-For";

  public static final Set<String> PROTECTED_TENANT_IDS =
      Set.of(SYSTEM_TENANT_ID, DEFAULT_TEMPLATE_TENANT_ID, DEFAULT_TENANT_ID, "ta", "tb", "tc");
  public static final Set<String> HIDDEN_TENANT_IDS =
      Set.of(DEFAULT_TEMPLATE_TENANT_ID, DEFAULT_TENANT_ID);
  public static final List<String> DEV_FIXTURE_TENANT_IDS =
      List.of("ta", "tb", "tc", "tx", DEFAULT_TENANT_ID);
  public static final List<String> RESERVED_TENANT_IDS =
      List.of(SYSTEM_TENANT_ID, DEFAULT_TEMPLATE_TENANT_ID, ADMIN_TENANT_ID);

  private CommonConstants() {}
}
