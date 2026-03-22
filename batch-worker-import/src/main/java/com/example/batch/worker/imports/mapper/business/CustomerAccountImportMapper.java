package com.example.batch.worker.imports.mapper.business;

import java.util.Map;

public interface CustomerAccountImportMapper {

    int upsertCustomerAccount(Map<String, Object> params);
}
