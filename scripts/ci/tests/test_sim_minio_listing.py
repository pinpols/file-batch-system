# 用途：验证SIM MinIO 列表记录与空文件计数。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_sim_minio_listing.py。
import unittest

from scripts.sim.minio_listing import summarize_listing


class MinioListingTest(unittest.TestCase):
    def test_counts_files_and_detects_zero_byte_export(self):
        listing = "\n".join(
            (
                '{"type":"folder","key":"outbound/"}',
                '{"type":"file","key":"outbound/report.csv","size":128}',
                '{"type":"file","key":"outbound/empty.csv","size":0}',
            )
        )

        self.assertEqual((2, 1), summarize_listing(listing))

    def test_ignores_keep_markers_and_folders(self):
        listing = "\n".join(
            (
                '{"type":"folder","key":"outbound/"}',
                '{"type":"file","key":"outbound/.keep","size":0}',
            )
        )

        self.assertEqual((0, 0), summarize_listing(listing))

    def test_rejects_malformed_json_and_invalid_file_size(self):
        for listing in (
            "not json",
            '{"type":"file","key":"outbound/report.csv","size":-1}',
            '{"type":"file","key":"outbound/report.csv","size":"1"}',
        ):
            with self.subTest(listing=listing), self.assertRaises(ValueError):
                summarize_listing(listing)


if __name__ == "__main__":
    unittest.main()
