-- Stage 2e checkpoint 崩溃恢复场景的业务库清理。

DELETE FROM biz.customer_account
 WHERE tenant_id = 'ta'
   AND customer_no LIKE 'S2ECKPT%';
