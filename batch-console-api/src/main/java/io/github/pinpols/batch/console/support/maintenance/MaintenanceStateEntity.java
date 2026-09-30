package io.github.pinpols.batch.console.support.maintenance;

import java.time.Instant;
import lombok.Data;

/** {@code batch.console_maintenance_state} 的 MyBatis 行载体。 */
@Data
public class MaintenanceStateEntity {

  private Short id;
  private boolean enabled;
  private boolean readOnly;
  private String message;
  private Instant etaAt;
  private String affectedServicesJson;
  private long version;
  private String updatedBy;
  private Instant updatedAt;
}
