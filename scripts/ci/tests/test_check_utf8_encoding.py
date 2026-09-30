import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "check-utf8-encoding.py"
SPEC = importlib.util.spec_from_file_location("check_utf8_encoding", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class Utf8EncodingTest(unittest.TestCase):
    def test_rejects_ascii_utf16_text_even_when_utf8_can_decode_its_nul_bytes(self):
        data = "config: value".encode("utf-16-le")
        self.assertIn("NUL byte", MODULE.encoding_error("config/example.yml", data))

    def test_allows_utf8_bom_only_for_registered_fixture(self):
        data = MODULE.UTF8_BOM + "name,value\n".encode("utf-8")
        self.assertIsNone(MODULE.encoding_error(next(iter(MODULE.BOM_ALLOWLIST)), data))
        self.assertIn("BOM is not allowed", MODULE.encoding_error("config/example.csv", data))


if __name__ == "__main__":
    unittest.main()
