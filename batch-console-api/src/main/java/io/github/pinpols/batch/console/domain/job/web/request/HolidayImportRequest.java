package io.github.pinpols.batch.console.domain.job.web.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Data;

@Data
public class HolidayImportRequest {
  @NotNull private String tenantId;

  @NotEmpty @Valid private List<HolidayItem> items;

  @Data
  public static class HolidayItem {
    @NotNull private String bizDate;
    @NotNull private String dayType;
    private String holidayName;
    private String description;
  }
}
