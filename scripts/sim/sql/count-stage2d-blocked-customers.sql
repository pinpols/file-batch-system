SELECT count(*)
FROM biz.customer_account
WHERE tenant_id = :'tenant_id'
  AND customer_no IN (
      'S2DSKIPBAD001',
      'S2DSKIPEXBAD001',
      'S2DSKIPEXBAD002',
      'S2DSKIPEXOK001'
  );
