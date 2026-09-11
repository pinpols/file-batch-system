INSERT INTO biz.import_copy_worth_bench (
    tenant_id,row_key,c01,c02,c03,c04,c05,c06,c07,c08,c09,c10,c11,c12,c13,c14,c15,
    clong1,clong2,n01,n02,n03,n04,n05
)
SELECT tenant_id,row_key,c01,c02,c03,c04,c05,c06,c07,c08,c09,c10,c11,c12,c13,c14,c15,
       clong1,clong2,n01,n02,n03,n04,n05
FROM import_copy_stage
ON CONFLICT (tenant_id,row_key) DO UPDATE SET
    c01=excluded.c01,c02=excluded.c02,c03=excluded.c03,c04=excluded.c04,c05=excluded.c05,
    c06=excluded.c06,c07=excluded.c07,c08=excluded.c08,c09=excluded.c09,c10=excluded.c10,
    c11=excluded.c11,c12=excluded.c12,c13=excluded.c13,c14=excluded.c14,c15=excluded.c15,
    clong1=excluded.clong1,clong2=excluded.clong2,
    n01=excluded.n01,n02=excluded.n02,n03=excluded.n03,n04=excluded.n04,n05=excluded.n05;
