CREATE TABLE biz.import_copy_worth_bench (
    tenant_id text NOT NULL,
    row_key text NOT NULL,
    c01 text, c02 text, c03 text, c04 text, c05 text,
    c06 text, c07 text, c08 text, c09 text, c10 text,
    c11 text, c12 text, c13 text, c14 text, c15 text,
    clong1 text, clong2 text,
    n01 numeric(18,2), n02 numeric(18,2), n03 numeric(18,2),
    n04 numeric(18,2), n05 numeric(18,2),
    PRIMARY KEY (tenant_id, row_key)
);
