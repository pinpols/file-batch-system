#!/usr/bin/env python3
"""Tests for readiness documentation drift classification."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).parents[1] / "check-readiness-doc-sync.py"
SPEC = importlib.util.spec_from_file_location("check_readiness_doc_sync", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ReadinessDocSyncTest(unittest.TestCase):

    def test_safe_exception_summary_replacement_is_logging_only(self) -> None:
        diff = """diff --git a/X.java b/X.java
--- a/X.java
+++ b/X.java
@@ -1 +1,2 @@
+import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
-          e.getMessage());
+          SwallowedExceptionLogger.summary(e));
"""
        with patch.object(MODULE, "run_git", return_value=diff):
            self.assertTrue(MODULE.is_logging_only_change("X.java", "origin/main"))

    def test_business_change_is_not_logging_only(self) -> None:
        diff = """diff --git a/X.java b/X.java
--- a/X.java
+++ b/X.java
@@ -1 +1 @@
-return false;
+return true;
"""
        with patch.object(MODULE, "run_git", return_value=diff):
            self.assertFalse(MODULE.is_logging_only_change("X.java", "origin/main"))


if __name__ == "__main__":
    unittest.main()
