package com.example.batch.console.web.query;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class PageQueryRequest {

    @Min(1)
    private Integer pageNo = 1;

    @Min(1)
    @Max(500)
    private Integer pageSize = 20;
}
