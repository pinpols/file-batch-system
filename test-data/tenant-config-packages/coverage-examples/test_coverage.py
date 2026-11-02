#!/usr/bin/env python3
"""校验已发布三包的覆盖与图约束；不连接服务、不写入业务数据。"""
from collections import Counter, deque
import json
from pathlib import Path
import unittest
from openpyxl import load_workbook

HERE = Path(__file__).resolve().parent
EXPECTED_JOB_TYPES = {'GENERAL', 'IMPORT', 'EXPORT', 'PROCESS', 'DISPATCH', 'ATOMIC',
                      'BUNDLE_IMPORT', 'BUNDLE_EXPORT', 'BUNDLE_DISPATCH'}
EXPECTED_NODE_TYPES = {'START', 'END', 'JOB', 'GATEWAY', 'WAIT'}
EXPECTED_EDGE_TYPES = {'SUCCESS', 'FAILURE', 'CONDITION', 'ALWAYS'}
DATA_SHEETS = {'resource_queue', 'business_calendar', 'batch_window', 'job_definition',
               'job_monitoring_policy', 'file_channel_config', 'file_template_config',
               'workflow_definition', 'workflow_node', 'workflow_edge', 'workflow_monitoring_policy'}


def rows(workbook, name):
    it = iter(workbook[name].values)
    headers = next(it)
    return [dict(zip(headers, row)) for row in it
            if any(cell is not None and str(cell).strip() for cell in row)]


class TenantCoveragePackagesTest(unittest.TestCase):
    def test_packages_cover_current_types_and_graphs_without_auto_schedule(self):
        for tenant, join_mode in [('ta', 'ALL'), ('tb', 'ANY'), ('tc', 'N_OF')]:
            with self.subTest(tenant=tenant):
                path = HERE / f'{tenant}-coverage-example.xlsx'
                wb = load_workbook(path, read_only=True, data_only=True)
                self.assertTrue(DATA_SHEETS.issubset(wb.sheetnames))
                self.assertFalse({'pipeline_definition', 'pipeline_step_definition'} & set(wb.sheetnames))
                for sheet in DATA_SHEETS:
                    self.assertTrue(all(r.get('tenant_id') == tenant for r in rows(wb, sheet)))
                jobs = rows(wb, 'job_definition')
                nodes = rows(wb, 'workflow_node')
                edges = rows(wb, 'workflow_edge')
                workflows = rows(wb, 'workflow_definition')
                self.assertEqual(Counter(r['job_type'] for r in jobs), Counter(EXPECTED_JOB_TYPES))
                self.assertTrue(all(r['schedule_type'] == 'MANUAL' for r in jobs))
                general = next(r for r in jobs if r['job_type'] == 'GENERAL')
                self.assertIn(general['enabled'], (False, 'false', 'FALSE'))
                self.assertTrue(all(r['schedule_enabled'] in (False, 'false', 'FALSE') for r in workflows))
                self.assertEqual({r['node_type'] for r in nodes}, EXPECTED_NODE_TYPES)
                self.assertEqual({r['edge_type'] for r in edges}, EXPECTED_EDGE_TYPES)
                self.assertTrue(any(r.get('depends_on_job_code') for r in jobs))
                self.assertTrue(any(r.get('depends_on_job_code') for r in workflows))
                branch = next(r for r in nodes if r['node_code'] == 'MERGE')
                self.assertEqual(json.loads(branch['node_params'])['joinMode'], join_mode)
                self.assertEqual(next(r for r in edges if r['edge_type'] == 'CONDITION')['condition_expr'], 'proceed == true')
                wait = json.loads(next(r for r in nodes if r['node_type'] == 'WAIT')['node_params'])
                self.assertEqual(wait['sensor_type'], 'FILE_ARRIVAL')
                self.assertGreater(wait['timeout_seconds'], wait['poll_interval_seconds'])
                codes = {r['job_code'] for r in jobs}
                self.assertTrue(all(not r.get('related_job_code') or r['related_job_code'] in codes for r in nodes))
                for wf in workflows:
                    group = [r for r in nodes if r['workflow_code'] == wf['workflow_code']]
                    links = [r for r in edges if r['workflow_code'] == wf['workflow_code']]
                    names = {r['node_code'] for r in group}
                    self.assertEqual(sum(r['node_type'] == 'START' for r in group), 1)
                    self.assertGreaterEqual(sum(r['node_type'] == 'END' for r in group), 1)
                    degree = {name: 0 for name in names}
                    adjacency = {name: [] for name in names}
                    for link in links:
                        self.assertIn(link['from_node_code'], names)
                        self.assertIn(link['to_node_code'], names)
                        adjacency[link['from_node_code']].append(link['to_node_code'])
                        degree[link['to_node_code']] += 1
                    start = next(r['node_code'] for r in group if r['node_type'] == 'START')
                    reachable = {start}
                    frontier = deque([start])
                    while frontier:
                        for following in adjacency[frontier.popleft()]:
                            if following not in reachable:
                                reachable.add(following)
                                frontier.append(following)
                    self.assertEqual(reachable, names, wf['workflow_code'])
                    pending = deque(name for name, count in degree.items() if count == 0)
                    ordered = []
                    while pending:
                        name = pending.popleft()
                        ordered.append(name)
                        for other in adjacency[name]:
                            degree[other] -= 1
                            if degree[other] == 0:
                                pending.append(other)
                    self.assertEqual(len(ordered), len(names), wf['workflow_code'])
                wb.close()


if __name__ == '__main__':
    unittest.main()
