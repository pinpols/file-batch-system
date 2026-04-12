package com.example.batch.console.web.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateUserAccountRequest {

  @Size(max = 256)
  private String displayName;

  @Size(max = 512)
  private String authoritiesCsv;
}
