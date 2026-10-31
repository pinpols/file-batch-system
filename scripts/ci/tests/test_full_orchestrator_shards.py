# 用途：核对 Full CI 编排长类分片及必需结果/覆盖率汇合。
# 用法：python3 -m unittest scripts.ci.tests.test_full_orchestrator_shards
from pathlib import Path
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[3]


class FullOrchestratorShardsTest(unittest.TestCase):
    def test_long_classes_exist_once_and_are_used_for_both_selections(self):
        names = (ROOT / 'scripts/ci/test-shards/full-orchestrator-heavy.txt').read_text().splitlines()
        self.assertTrue(names)
        self.assertEqual(len(names), len(set(names)))
        sources = [source for module in ('batch-orchestrator', 'batch-worker/core', 'sdk/java/core',
                                        'sdk/java/spring', 'sdk/java/testkit')
                   for source in (ROOT / module / 'src/test/java').rglob('*.java')]
        for name in names:
            self.assertRegex(name, r'^[A-Za-z][A-Za-z0-9]*IntegrationTest$')
            self.assertEqual(1, sum(bool(re.search(r'\bclass\s+' + re.escape(name) + r'\b',
                                                   f.read_text(encoding='utf-8'))) for f in sources), name)
        workflow = (ROOT / '.github/workflows/full-ci-gate.yml').read_text(encoding='utf-8')
        main = workflow.split('  unit-it-a-main:\n', 1)[1].split('  unit-it-a-heavy:\n', 1)[0]
        heavy = workflow.split('  unit-it-a-heavy:\n', 1)[1].split('  unit-it-a:\n', 1)[0]
        self.assertIn("sed 's/^/!/' scripts/ci/test-shards/full-orchestrator-heavy.txt", main)
        self.assertIn('paste -sd, scripts/ci/test-shards/full-orchestrator-heavy.txt', heavy)
        self.assertIn('-Dsurefire.failIfNoSpecifiedTests=true', heavy)

    def test_join_rejects_incomplete_shards_and_requires_both_coverage_files(self):
        workflow = (ROOT / '.github/workflows/full-ci-gate.yml').read_text(encoding='utf-8')
        join = workflow.split('  unit-it-a:\n', 1)[1].split('  # ===', 1)[0]
        self.assertIn('needs: [unit-it-a-main, unit-it-a-heavy]', join)
        guard = '[[ "$MAIN_RESULT" == "success" && "$HEAVY_RESULT" == "success" ]]'
        self.assertIn(guard, join)
        for main in ('success', 'failure', 'skipped', 'cancelled'):
            for heavy in ('success', 'failure', 'skipped', 'cancelled'):
                result = subprocess.run(['bash', '-c', guard], env={'MAIN_RESULT': main, 'HEAVY_RESULT': heavy})
                self.assertEqual(main == heavy == 'success', result.returncode == 0)
        for name in ('main', 'heavy'):
            self.assertIn(f'ci-jacoco-shards/{name}/jacoco.exec', join)
        self.assertIn('jacoco:merge@merge-ci-shards', join)
        self.assertIn('jacoco:check@check jacoco:report', join)
        self.assertIn('--path batch-orchestrator', join)


if __name__ == '__main__':
    unittest.main()
