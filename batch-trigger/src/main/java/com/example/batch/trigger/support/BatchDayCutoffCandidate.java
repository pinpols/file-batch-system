package com.example.batch.trigger.support;

import java.time.LocalDate;
import java.time.LocalTime;
import lombok.Data;

@Data
public class BatchDayCutoffCandidate {

  private Long id;
  private String tenantId;
  private String calendarCode;
  private LocalDate bizDate;
  private String dayStatus;
  private String timezone;
  private LocalTime cutoffTime;
}
