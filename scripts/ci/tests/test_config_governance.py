# 用途：配置治理清单只索引受版本控制的生产源码，不扫描构建快照。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_config_governance.py。
"""配置治理清单只索引受版本控制的生产源码，不扫描构建快照。"""

import importlib.util
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "scripts/ci/check-config-governance.py"
SPEC = importlib.util.spec_from_file_location("check_config_governance", SCRIPT)
CONFIG_GOVERNANCE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONFIG_GOVERNANCE)


class ConfigGovernanceInventoryTest(unittest.TestCase):
    def test_inventory_excludes_build_and_target_snapshots(self):
        java_files = CONFIG_GOVERNANCE.production_java_files()
        relative_parts = [path.relative_to(ROOT).parts for path in java_files]

        self.assertTrue(java_files)
        self.assertTrue(
            all("build" not in parts and "target" not in parts for parts in relative_parts)
        )


if __name__ == "__main__":
    unittest.main()
