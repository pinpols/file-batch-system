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


    def test_test_source_path_matching_production_class_is_not_touchpoint(self) -> None:
        """测试文件路径与生产类同名（DefaultTriggerServiceTest）不应触发契约证据要求。"""
        diff = """diff --git a/X.java b/X.java
--- a/X.java
+++ b/X.java
@@ -1 +1,2 @@
+import org.junit.jupiter.api.DisplayName;
+  @DisplayName("触发服务:幂等与审批")
"""
        path = (
            "batch-trigger/src/test/java/io/github/pinpols/batch/trigger/service/"
            "DefaultTriggerServiceTest.java"
        )
        with patch.object(MODULE, "run_git", return_value=diff):
            self.assertFalse(MODULE.is_readiness_touchpoint(path, "origin/main"))

    def test_production_path_is_still_touchpoint(self) -> None:
        path = (
            "batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/"
            "DefaultTriggerService.java"
        )
        with patch.object(MODULE, "run_git", return_value=""):
            self.assertTrue(MODULE.is_readiness_touchpoint(path, "origin/main"))

    def test_test_source_with_readiness_content_is_still_touchpoint(self) -> None:
        """路径豁免不能放过测试侧真实的 readiness 内容改动。"""
        diff = """diff --git a/X.java b/X.java
--- a/X.java
+++ b/X.java
@@ -1 +1 @@
+      readiness_timeout = 30;
"""
        path = (
            "batch-trigger/src/test/java/io/github/pinpols/batch/trigger/service/"
            "DefaultTriggerServiceTest.java"
        )
        with patch.object(MODULE, "run_git", return_value=diff):
            self.assertTrue(MODULE.is_readiness_touchpoint(path, "origin/main"))


if __name__ == "__main__":
    unittest.main()
