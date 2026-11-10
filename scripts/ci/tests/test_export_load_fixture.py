# 用途：核对压测结算导出模板的文件列与分页游标配置。
# 用法：在仓库根目录执行 python3 -m unittest scripts.ci.tests.test_export_load_fixture。
"""验证实际 SQL 夹具中的导出文件契约，不连接数据库。"""

import json
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[3]
FIXTURE = ROOT / "load-tests/sql/prepare-worker-load-platform.sql"


class ExportLoadFixtureTest(unittest.TestCase):
    def test_settlement_export_selects_nine_business_columns_without_cursor(self):
        sql = FIXTURE.read_text(encoding="utf-8")
        template = re.search(
            r"\(\s*'default-tenant',\s*'export_settlement_v1'(?P<body>.*?)\)\s*ON CONFLICT",
            sql,
            re.DOTALL,
        )
        self.assertIsNotNone(template, "未找到结算导出模板")
        json_objects = re.findall(r"'(\{[^']*\})'::jsonb", template.group("body"), re.DOTALL)
        self.assertEqual(1, len(json_objects), "无法唯一确定导出查询配置")
        schema = json.loads(json_objects[0])
        self.assertEqual("id", schema["sqlTemplateExport"]["cursorColumn"])
        self.assertIn("csvColumns", schema, "结算导出必须显式配置文件列")
        self.assertEqual(
            [
                ("batch_no", "batchNo"),
                ("biz_date", "bizDate"),
                ("settlement_no", "settlementNo"),
                ("customer_no", "customerNo"),
                ("gross_amount", "grossAmount"),
                ("fee_amount", "feeAmount"),
                ("net_amount", "netAmount"),
                ("currency", "currency"),
                ("settlement_status", "status"),
            ],
            [(column["source"].removeprefix("detail."), column["header"])
             for column in schema["csvColumns"]],
        )


if __name__ == "__main__":
    unittest.main()
