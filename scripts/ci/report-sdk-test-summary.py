#!/usr/bin/env python3
# 用途：汇总 SDK 各语言、版本和真实传输测试的退出状态与原始日志。
# 用法：python3 scripts/ci/report-sdk-test-summary.py --reports-root <目录> --jobs-json <文件> --output <目录>。
"""汇总 SDK 并发任务，不把缺失或跳过当作通过。"""
import argparse
import json
import os
from pathlib import Path

CASES = [
    ('java-contract', 'sdk-test-java-reports', 'java-contract'),
    *[('python-contract', f'sdk-test-python-{version}-reports', case)
      for version in ('3.12', '3.14') for case in ('python-contract', 'python-constants')],
    *[('typescript-contract', f'sdk-test-typescript-{version}-reports', 'typescript-contract')
      for version in ('22', '24')],
    *[('go-contract', f'sdk-test-go-{version}-reports', 'go-contract')
      for version in ('1.26.x', '1.27.x')],
    *[('rust-contract', 'sdk-test-rust-reports', case)
      for case in ('rust-msrv', 'rust-default', 'rust-http')],
    ('sdk-live-transport', 'sdk-test-live-reports', 'sdk-live-transport'),
]


def summarize(root, jobs):
    rows, missing = [], []
    for job, artifact, case in CASES:
        state = jobs.get(job, 'unknown')
        log, exit_file = root / artifact / f'{case}.log', root / artifact / f'{case}.exit'
        if log.is_file() and exit_file.is_file():
            exit_code = int(exit_file.read_text(encoding='utf-8').strip())
            status = 'exit-0' if exit_code == 0 else 'exit-nonzero'
        else:
            exit_code = None
            status = 'skipped' if state == 'skipped' else 'not-run'
            if state == 'success':
                status = 'missing'
                missing.append(f'{artifact}/{case}')
        rows.append({'job': job, 'jobStatus': state, 'artifact': artifact, 'case': case,
                     'status': status, 'exitCode': exit_code})
    return rows, missing


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--reports-root', type=Path, required=True)
    parser.add_argument('--jobs-json', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    jobs = {name: value.get('result', 'unknown')
            for name, value in json.loads(args.jobs_json.read_text(encoding='utf-8')).items()}
    rows, missing = summarize(args.reports_root, jobs)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'summary.json').write_text(json.dumps({'jobs': jobs, 'tests': rows,
                                                          'missingReports': missing}, indent=2) + '\n')
    lines = ['## SDK test report', '', '| Test | Artifact | Test status | Job status |',
             '|---|---|---|---|']
    lines.extend(f"| {row['case']} | {row['artifact']} | {row['status']} | {row['jobStatus']} |" for row in rows)
    lines.extend(['', 'Exit status is for the test command, not a per-case pass count; '
                  'inspect named logs for skipped and individual results. Missing and skipped are not passes.', ''])
    summary = '\n'.join(lines)
    (args.output / 'summary.md').write_text(summary, encoding='utf-8')
    if target := os.environ.get('GITHUB_STEP_SUMMARY'):
        with Path(target).open('a', encoding='utf-8') as stream:
            stream.write(summary)
    print(summary)
    return 1 if missing else 0


if __name__ == '__main__':
    raise SystemExit(main())
