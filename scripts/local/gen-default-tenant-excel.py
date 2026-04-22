#!/usr/bin/env python3
# =====================================================================
# gen-default-tenant-excel.py
# 依据 multi-tenant-seed.sql 里 default-tenant 的 "v4 硬化批次" 新增项
# 生成与 ta/tb/tc-tenant-config-package-test.xlsx 同结构的配置包 Excel，
# 覆盖 4 条 channel_config + 3 条 wf_probe workflow（PIPELINE / DAG+GATEWAY /
# MIXED）+ 对应 job_definition。其他 sheet 仅保留表头占位。
# =====================================================================
import json
from openpyxl import Workbook

OUT = "docs/test-data/test-full-coverage-import-suite/default-tenant-config-package-test.xlsx"

JOB_COLS = [
    "tenant_id","job_code","job_name","job_type","biz_type","queue_code","worker_group",
    "schedule_type","schedule_expr","calendar_code","window_code","retry_policy",
    "retry_max_count","timeout_seconds","shard_strategy","execution_handler",
    "param_schema","default_params","enabled","description",
]
CHANNEL_COLS = [
    "tenant_id","channel_code","channel_name","channel_type","target_endpoint",
    "auth_type","config_json","receipt_policy","timeout_seconds","enabled",
]
ROUTING_COLS = [
    "tenant_id","route_code","route_name","team","alert_group","severity","receiver",
    "group_by","group_wait_seconds","group_interval_seconds","repeat_interval_seconds",
    "enabled","description",
]
PIPELINE_COLS = [
    "tenant_id","job_code","pipeline_name","pipeline_type","biz_type","worker_group",
    "version","enabled","description",
]
STEP_COLS = [
    "job_code","version","step_code","step_name","stage_code","step_order","impl_code",
    "step_params","timeout_seconds","retry_policy","retry_max_count","enabled",
]
WF_DEF_COLS = [
    "tenant_id","workflow_code","workflow_name","workflow_type","version","enabled","description",
]
WF_NODE_COLS = [
    "tenant_id","workflow_code","workflow_version","node_code","node_name","node_type",
    "related_job_code","related_pipeline_code","worker_group","window_code",
    "node_order","retry_policy","retry_max_count","timeout_seconds","node_params","enabled",
]
WF_EDGE_COLS = [
    "tenant_id","workflow_code","workflow_version","from_node_code","to_node_code",
    "edge_type","condition_expr","enabled",
]

TENANT = "default-tenant"

def jd(job_code, job_name):
    # 与 seed SQL `INSERT INTO batch.job_definition` (line 534-540, 572-577) 对齐：
    # job_type=WORKFLOW, schedule_type=MANUAL, queue_code=export_queue,
    # worker_group=EXPORT, window_code=always_open, priority=5
    return [
        TENANT, job_code, job_name, "WORKFLOW", None, "export_queue", "EXPORT",
        "MANUAL", None, None, "always_open", "NONE",
        0, 14400, "NONE", None,
        "{}", "{}", "TRUE", f"P2 seed aligned - {job_name}",
    ]

JOB_ROWS = [
    jd("wf_probe_pipeline", "Probe PIPELINE"),
    jd("wf_probe_gateway",  "Probe GATEWAY"),
    jd("wf_probe_mixed",    "Probe MIXED"),
]

def ch(code, name, ctype, endpoint, cfg, auth="NONE", receipt="NONE", timeout=30):
    return [TENANT, code, name, ctype, endpoint, auth, json.dumps(cfg, ensure_ascii=False),
            receipt, timeout, "TRUE"]

CHANNEL_ROWS = [
    ch("sftp_bank", "SFTP Bank Dispatch", "SFTP", "localhost:12222", {
        "target_endpoint":"localhost:12222","sftp_host":"localhost","sftp_port":12222,
        "sftp_user":"ta","sftp_password":"ta_pass_123",
        "sftp_remote_directory":"/inbound","sftp_strict_host_key_checking":"no",
    }),
    ch("email_ops", "Email Ops Dispatch", "EMAIL", "localhost:1025", {
        "target_endpoint":"localhost:1025","smtp_host":"localhost","smtp_port":1025,
        "smtp_starttls":False,"mail_from":"batch@local.dev","mail_to":"ops@local.dev",
        "mail_subject":"Batch Dispatch Probe",
    }),
    ch("nas_archive", "NAS Archive Dispatch", "NAS", "/tmp/batch/nas-probe", {
        "target_endpoint":"/tmp/batch/nas-probe",
        "nas_remote_directory":"/tmp/batch/nas-probe",
        "nas_remote_file_name":"settlement-{bizDate}.csv",
    }),
    ch("oss_backup", "OSS Backup Dispatch", "OSS", "http://localhost:19000", {
        "target_endpoint":"http://localhost:19000","oss_bucket":"batch-dev",
        "oss_object_prefix":"dispatch/oss-probe/",
    }),
]

def wdef(code, name, wftype, desc):
    return [TENANT, code, name, wftype, 1, "TRUE", desc]

WF_DEF_ROWS = [
    wdef("wf_probe_pipeline","Probe PIPELINE workflow","PIPELINE","P2 seed - PIPELINE type"),
    wdef("wf_probe_gateway", "Probe GATEWAY + ANY join","DAG",    "P2 seed - GATEWAY + ANY join"),
    wdef("wf_probe_mixed",   "Probe MIXED workflow","MIXED",      "P2 seed - MIXED type (TASK + FILE_STEP mixed)"),
]

def wn(wf, nc, nn, nt, rjc, order, params):
    return [TENANT, wf, 1, nc, nn, nt, rjc, None, None, None,
            order, "NONE", 0, 0, json.dumps(params, ensure_ascii=False), "TRUE"]

WF_NODE_ROWS = [
    wn("wf_probe_pipeline","START","Start","START",None,0,{"entry":True}),
    wn("wf_probe_pipeline","PROBE_TASK","Probe Task","TASK","exp_settlement_daily",1,{"step":"probe"}),
    wn("wf_probe_pipeline","END","End","END",None,2,{"entry":False}),
    wn("wf_probe_gateway","START","Start","START",None,0,{"entry":True}),
    wn("wf_probe_gateway","FORK","Fork Gateway","GATEWAY",None,1,{}),
    wn("wf_probe_gateway","BRANCH_A","Branch A","TASK","exp_settlement_daily",2,{"step":"branchA"}),
    wn("wf_probe_gateway","BRANCH_B","Branch B","TASK","exp_settlement_daily",3,{"step":"branchB"}),
    wn("wf_probe_gateway","MERGE","Merge Gateway","GATEWAY",None,4,{"joinMode":"ANY"}),
    wn("wf_probe_gateway","END","End","END",None,5,{"entry":False}),
    wn("wf_probe_mixed","START","Start","START",None,0,{"entry":True}),
    wn("wf_probe_mixed","PROCESS","Process Task","TASK","exp_settlement_daily",1,{"step":"process"}),
    wn("wf_probe_mixed","REPORT","Generate Report","FILE_STEP","exp_settlement_daily",2,{"step":"report"}),
    wn("wf_probe_mixed","END","End","END",None,3,{"entry":False}),
]

def we(wf, f, t, et):
    return [TENANT, wf, 1, f, t, et, None, "TRUE"]

WF_EDGE_ROWS = [
    we("wf_probe_pipeline","START","PROBE_TASK","ALWAYS"),
    we("wf_probe_pipeline","PROBE_TASK","END","ALWAYS"),
    we("wf_probe_gateway","START","FORK","ALWAYS"),
    we("wf_probe_gateway","FORK","BRANCH_A","ALWAYS"),
    we("wf_probe_gateway","FORK","BRANCH_B","ALWAYS"),
    we("wf_probe_gateway","BRANCH_A","MERGE","SUCCESS"),
    we("wf_probe_gateway","BRANCH_B","MERGE","SUCCESS"),
    we("wf_probe_gateway","MERGE","END","ALWAYS"),
    we("wf_probe_mixed","START","PROCESS","ALWAYS"),
    we("wf_probe_mixed","PROCESS","REPORT","SUCCESS"),
    we("wf_probe_mixed","REPORT","END","ALWAYS"),
]

def write_sheet(wb, name, columns, rows):
    ws = wb.create_sheet(title=name)
    ws.append(columns)
    for r in rows:
        ws.append(r)

def main():
    wb = Workbook()
    wb.remove(wb.active)
    write_sheet(wb, "job_definition",           JOB_COLS,     JOB_ROWS)
    write_sheet(wb, "file_channel_config",      CHANNEL_COLS, CHANNEL_ROWS)
    write_sheet(wb, "alert_routing_config",     ROUTING_COLS, [])
    write_sheet(wb, "pipeline_definition",      PIPELINE_COLS,[])
    write_sheet(wb, "pipeline_step_definition", STEP_COLS,    [])
    write_sheet(wb, "workflow_definition",      WF_DEF_COLS,  WF_DEF_ROWS)
    write_sheet(wb, "workflow_node",            WF_NODE_COLS, WF_NODE_ROWS)
    write_sheet(wb, "workflow_edge",            WF_EDGE_COLS, WF_EDGE_ROWS)
    wb.save(OUT)
    print(f"wrote {OUT}")

if __name__ == "__main__":
    main()
