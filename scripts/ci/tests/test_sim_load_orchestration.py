import unittest

from scripts.sim.load_orchestration import (
    launch_exports_after_imports,
    wait_for_import_terminal,
)


class SimLoadOrchestrationTest(unittest.TestCase):
    def test_waits_for_import_terminal_before_launching_exports(self):
        events = []
        statuses = {"ta": "SUCCESS", "tb": "SUCCESS"}

        def wait_import(tenant, job, request_id):
            events.append(("wait", tenant, request_id))
            return statuses[tenant]

        ready, errors = launch_exports_after_imports(
            tenants=("ta", "tb"),
            imports_by_tenant={"ta": (("TA_IMPORT", "ta-1"),), "tb": (("TB_IMPORT", "tb-1"),)},
            expected_import_counts={"ta": 1, "tb": 1},
            wait_import_terminal=wait_import,
            launch_export=lambda tenant: events.append(("export", tenant)),
            report=lambda _: None,
        )

        self.assertEqual({"ta": True, "tb": True}, ready)
        self.assertEqual([], errors)
        self.assertEqual(
            [
                ("wait", "ta", "ta-1"),
                ("wait", "tb", "tb-1"),
                ("export", "ta"),
                ("export", "tb"),
            ],
            events,
        )

    def test_skips_exports_when_import_failed_or_launch_was_rejected(self):
        exports = []
        ready, errors = launch_exports_after_imports(
            tenants=("ta", "tb"),
            imports_by_tenant={"ta": (("TA_IMPORT", "ta-1"),), "tb": ()},
            expected_import_counts={"ta": 1, "tb": 1},
            wait_import_terminal=lambda *_: "FAILED",
            launch_export=exports.append,
            report=lambda _: None,
        )

        self.assertEqual({"ta": False, "tb": False}, ready)
        self.assertEqual(
            ["IMPORT ta/TA_IMPORT did not reach SUCCESS before dependent EXPORT: FAILED"], errors
        )
        self.assertEqual([], exports)

    def test_one_failed_tenant_does_not_block_another_tenants_export(self):
        exports = []
        ready, errors = launch_exports_after_imports(
            tenants=("ta", "tb"),
            imports_by_tenant={"ta": (("TA_IMPORT", "ta-1"),), "tb": (("TB_IMPORT", "tb-1"),)},
            expected_import_counts={"ta": 1, "tb": 1},
            wait_import_terminal=lambda tenant, *_: "SUCCESS" if tenant == "ta" else "FAILED",
            launch_export=exports.append,
            report=lambda _: None,
        )

        self.assertEqual({"ta": True, "tb": False}, ready)
        self.assertEqual(["ta"], exports)
        self.assertEqual(
            ["IMPORT tb/TB_IMPORT did not reach SUCCESS before dependent EXPORT: FAILED"], errors
        )

    def test_wait_for_import_returns_terminal_failure_and_times_out(self):
        statuses = iter(("RUNNING", "FAILED"))
        times = iter((0, 1, 2, 3))
        self.assertEqual(
            "FAILED",
            wait_for_import_terminal(
                "ta", "TA_IMPORT", "request-1", lambda *_: next(statuses),
                timeout_seconds=3, poll_interval_seconds=1,
                now=times.__next__, pause=lambda _: None,
            ),
        )
        self.assertEqual(
            "TIMEOUT",
            wait_for_import_terminal(
                "ta", "TA_IMPORT", "request-1", lambda *_: "RUNNING",
                timeout_seconds=2, poll_interval_seconds=1,
                now=iter((0, 1, 2)).__next__, pause=lambda _: None,
            ),
        )


if __name__ == "__main__":
    unittest.main()
