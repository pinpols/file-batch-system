-- BATCH_UPSERT 的重放安全性是模板的强制约束，运行时开关不得绕过。

DELETE FROM batch.batch_runtime_default_parameter
WHERE module = 'WORKER_IMPORT'
  AND parameter_key = 'jdbc_mapped.strict_idempotency';
