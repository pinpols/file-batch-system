package com.example.batch.common.model;

public record PageRequest(int pageNo, int pageSize) {
    public PageRequest {
        if (pageNo < 1) {
            pageNo = 1;
        }
        if (pageSize < 1) {
            pageSize = 20;
        }
    }
}
