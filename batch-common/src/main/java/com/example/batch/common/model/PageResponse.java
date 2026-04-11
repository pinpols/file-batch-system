package com.example.batch.common.model;

import java.util.List;

public record PageResponse<T>(long total, int pageNo, int pageSize, List<T> items) {}
