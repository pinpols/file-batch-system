SELECT count(*)
FROM biz.risk_score
WHERE tenant_id = :'tenant_id'
  AND left(entity_id, length('ENT-' || :'batch_no' || '-')) = 'ENT-' || :'batch_no' || '-';
