CREATE TEMP TABLE import_copy_stage
    (LIKE biz.import_copy_worth_bench INCLUDING DEFAULTS)
    ON COMMIT DROP;
