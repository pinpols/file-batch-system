SELECT count(*)
FROM biz.transaction
WHERE tenant_id = :'tenant_id'
  AND left(txn_no, length('TB-TXN-' || :'batch_no' || '-')) = 'TB-TXN-' || :'batch_no' || '-';
