#!/usr/bin/env python3
# 用途：按 "租户分工" 策略向 ta / tb / tc-tenant-config-package-test.xlsx 追加。
# 用法：在仓库根目录执行 python3 scripts/local/append-tenant-coverage.py；参数与前置环境见下方说明及所属 README。
# =====================================================================
# append-tenant-coverage.py
# 按 "租户分工" 策略向 ta / tb / tc-tenant-config-package-test.xlsx 追加
# v4 场景覆盖，补齐枚举 + 场景长尾：
#   - ta (零售)：追加 IMPORT Job + LOCAL channel
#   - tb (金融)：追加 DISPATCH Job + API channel
#   - tc (风控)：GATEWAY ALL / N_OF join 两个 workflow + FAILURE / CONDITION 边
# default-tenant 继续承载 MIXED + 探针，由 gen-default-tenant-excel.py 管。
#
# 幂等：追加前按 "主键" 查重跳过；重复跑不会产生重复行。
# 调用示例：python3 scripts/local/append-tenant-coverage.py
# =====================================================================
import json
from openpyxl import load_workbook

BASE = "test-data/tenant-config-packages/"

def row_exists(ws, key_col_idx, key_value):
    for row in ws.iter_rows(min_row=2, values_only=True):
        if row[key_col_idx] == key_value:
            return True
    return False

def composite_row_exists(ws, indices, values):
    for row in ws.iter_rows(min_row=2, values_only=True):
        if all(row[i] == v for i, v in zip(indices, values)):
            return True
    return False

def append_if_new(ws, key_cols, keys, row_values):
    """key_cols 指定用于识别记录的列下标，keys 为这些列的匹配值。"""
    if composite_row_exists(ws, key_cols, keys):
        return False
    ws.append(row_values)
    return True

# ──────────────────────────────────────────────────────────────
# ta (零售)
# ──────────────────────────────────────────────────────────────
def update_ta():
    path = BASE + "ta-tenant-config-package-test.xlsx"
    wb = load_workbook(path)

    # 新 IMPORT Job；阶段由系统 Pipeline 按 Job 类型固定提供。
    # job_definition 的列顺序：tenant_id, job_code, job_name, job_type, biz_type, queue_code, worker_group,
    #   schedule_type, schedule_expr, calendar_code, window_code, retry_policy, retry_max_count,
    #   timeout_seconds, shard_strategy, execution_handler, param_schema, default_params, enabled, description
    jd = wb["job_definition"]
    append_if_new(jd, [1], ["TA_IMPORT_ORDER"], [
        "ta","TA_IMPORT_ORDER","零售订单导入","IMPORT","ORDER","ta-import-queue","import",
        "CRON","0 3 * * *","default-calendar","always-open","EXPONENTIAL",3,3600,"STATIC",
        "orderImportHandler","{}","{}","TRUE","FEEDBACK 全链覆盖样本",
    ])
    # file_channel_config 的列顺序：tenant_id, channel_code, channel_name, channel_type, target_endpoint,
    #   auth_type, config_json, receipt_policy, timeout_seconds, enabled
    ch = wb["file_channel_config"]
    append_if_new(ch, [1], ["ta_local_archive"], [
        "ta","ta_local_archive","本地归档落盘","LOCAL","/tmp/batch/ta-archive","NONE",
        json.dumps({"target_endpoint":"/tmp/batch/ta-archive","local_directory":"/tmp/batch/ta-archive",
                    "local_file_name":"customer-{bizDate}.csv"}, ensure_ascii=False),
        "NONE",30,"TRUE",
    ])

    wb.save(path)
    print(f"ta: updated {path}")

# ──────────────────────────────────────────────────────────────
# tb (金融)
# ──────────────────────────────────────────────────────────────
def update_tb():
    path = BASE + "tb-tenant-config-package-test.xlsx"
    wb = load_workbook(path)

    jd = wb["job_definition"]
    append_if_new(jd, [1], ["TB_DISPATCH_SETTLE"], [
        "tb","TB_DISPATCH_SETTLE","对账单派发","DISPATCH","STATEMENT","tb-dispatch-queue","dispatch",
        "CRON","0 0 8 * * ?","default-calendar","always-open","EXPONENTIAL",5,3600,"NONE",
        "statementDispatchHandler","{}","{}","TRUE","DISPATCH 全链 6-stage 覆盖样本",
    ])

    ch = wb["file_channel_config"]
    append_if_new(ch, [1], ["tb_api_ingest"], [
        "tb","tb_api_ingest","核心系统 API 同步入站","API","http://mockserver:1080/tb/ingest",
        "TOKEN",
        json.dumps({"url":"http://mockserver:1080/tb/ingest","method":"POST",
                    "tokenHeader":"X-API-Token","target_endpoint":"http://mockserver:1080/tb/ingest"},
                   ensure_ascii=False),
        "SYNC",15,"TRUE",
    ])

    wb.save(path)
    print(f"tb: updated {path}")

# ──────────────────────────────────────────────────────────────
# tc (风控)
# ──────────────────────────────────────────────────────────────
def update_tc():
    path = BASE + "tc-tenant-config-package-test.xlsx"
    wb = load_workbook(path)

    wf = wb["workflow_definition"]
    append_if_new(wf, [1], ["TC_WF_GATEWAY_ALL"], [
        "tc","TC_WF_GATEWAY_ALL","风控 GATEWAY ALL 工作流","DAG",1,"TRUE","FORK→3 branch→MERGE(ALL)→END",
    ])
    append_if_new(wf, [1], ["TC_WF_GATEWAY_N_OF"], [
        "tc","TC_WF_GATEWAY_N_OF","风控 GATEWAY N_OF 工作流","DAG",1,"TRUE","FORK→3 branch→MERGE(N_OF=2)→END + FAILURE / CONDITION 边",
    ])

    wn = wb["workflow_node"]
    # node_params 以 JSON 字符串存储。
    def np(d): return json.dumps(d, ensure_ascii=False)
    # workflow_node 的列顺序：tenant_id, workflow_code, workflow_version, node_code, node_name, node_type,
    #   related_job_code, worker_group, window_code, node_order, retry_policy,
    #   retry_max_count, timeout_seconds, node_params, enabled
    all_nodes = [
        ("TC_WF_GATEWAY_ALL","START",    "Start",    "START",  None,                 0, {"entry":True}),
        ("TC_WF_GATEWAY_ALL","FORK",     "Fork",     "GATEWAY",None,                 1, {}),
        ("TC_WF_GATEWAY_ALL","BRANCH_A", "Branch A", "JOB",    "TC_IMPORT_RISK_SCORE",2, {"branch":"A"}),
        ("TC_WF_GATEWAY_ALL","BRANCH_B", "Branch B", "JOB",    "TC_EXPORT_RISK_ALERT",3, {"branch":"B"}),
        ("TC_WF_GATEWAY_ALL","BRANCH_C", "Branch C", "JOB",    "TC_DISPATCH_REVIEW",  4, {"branch":"C"}),
        ("TC_WF_GATEWAY_ALL","MERGE",    "Merge ALL","GATEWAY",None,                 5, {"joinMode":"ALL"}),
        ("TC_WF_GATEWAY_ALL","END",      "End",      "END",    None,                 6, {"entry":False}),

        ("TC_WF_GATEWAY_N_OF","START",    "Start",     "START",  None,                 0, {"entry":True}),
        ("TC_WF_GATEWAY_N_OF","FORK",     "Fork",      "GATEWAY",None,                 1, {}),
        ("TC_WF_GATEWAY_N_OF","BRANCH_A", "Branch A",  "JOB",    "TC_IMPORT_RISK_SCORE",2, {"branch":"A"}),
        ("TC_WF_GATEWAY_N_OF","BRANCH_B", "Branch B",  "JOB",    "TC_EXPORT_RISK_ALERT",3, {"branch":"B"}),
        ("TC_WF_GATEWAY_N_OF","BRANCH_C", "Branch C",  "JOB",    "TC_DISPATCH_REVIEW",  4, {"branch":"C"}),
        ("TC_WF_GATEWAY_N_OF","MERGE",    "Merge N_OF","GATEWAY",None,                 5, {"joinMode":"N_OF","joinThreshold":2}),
        ("TC_WF_GATEWAY_N_OF","END",      "End",       "END",    None,                 6, {"entry":False}),
        ("TC_WF_GATEWAY_N_OF","FALLBACK", "Fallback",  "JOB",    "TC_DISPATCH_REVIEW",  7, {"branch":"fallback"}),
    ]
    for wc, nc, nn, nt, rjc, order, params in all_nodes:
        append_if_new(wn, [1,3], [wc, nc], [
            "tc",wc,1,nc,nn,nt,rjc,None,None,None,order,"NONE",0,0,np(params),"TRUE",
        ])

    we = wb["workflow_edge"]
    # workflow_edge 的列顺序：tenant_id, workflow_code, workflow_version, from_node_code, to_node_code,
    #   edge_type, condition_expr, enabled
    edges = [
        ("TC_WF_GATEWAY_ALL","START","FORK","ALWAYS",None),
        ("TC_WF_GATEWAY_ALL","FORK","BRANCH_A","ALWAYS",None),
        ("TC_WF_GATEWAY_ALL","FORK","BRANCH_B","ALWAYS",None),
        ("TC_WF_GATEWAY_ALL","FORK","BRANCH_C","ALWAYS",None),
        ("TC_WF_GATEWAY_ALL","BRANCH_A","MERGE","SUCCESS",None),
        ("TC_WF_GATEWAY_ALL","BRANCH_B","MERGE","SUCCESS",None),
        ("TC_WF_GATEWAY_ALL","BRANCH_C","MERGE","SUCCESS",None),
        ("TC_WF_GATEWAY_ALL","MERGE","END","ALWAYS",None),
        # N_OF 工作流：补 FAILURE / CONDITION 边类型
        ("TC_WF_GATEWAY_N_OF","START","FORK","ALWAYS",None),
        ("TC_WF_GATEWAY_N_OF","FORK","BRANCH_A","ALWAYS",None),
        ("TC_WF_GATEWAY_N_OF","FORK","BRANCH_B","CONDITION","bizDate != null"),
        ("TC_WF_GATEWAY_N_OF","FORK","BRANCH_C","ALWAYS",None),
        ("TC_WF_GATEWAY_N_OF","BRANCH_A","MERGE","SUCCESS",None),
        ("TC_WF_GATEWAY_N_OF","BRANCH_B","MERGE","SUCCESS",None),
        ("TC_WF_GATEWAY_N_OF","BRANCH_C","MERGE","SUCCESS",None),
        ("TC_WF_GATEWAY_N_OF","BRANCH_C","FALLBACK","FAILURE",None),
        ("TC_WF_GATEWAY_N_OF","FALLBACK","END","ALWAYS",None),
        ("TC_WF_GATEWAY_N_OF","MERGE","END","ALWAYS",None),
    ]
    for wc, fnc, tnc, et, cond in edges:
        append_if_new(we, [1,3,4], [wc, fnc, tnc], [
            "tc",wc,1,fnc,tnc,et,cond,"TRUE",
        ])

    wb.save(path)
    print(f"tc: updated {path}")

if __name__ == "__main__":
    update_ta()
    update_tb()
    update_tc()
