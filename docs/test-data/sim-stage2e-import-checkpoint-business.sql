-- Stage 2e business DB cleanup for checkpoint crash-resume profile.

DELETE FROM biz.customer_account
 WHERE tenant_id = 'ta'
   AND customer_no LIKE 'S2ECKPT%';
