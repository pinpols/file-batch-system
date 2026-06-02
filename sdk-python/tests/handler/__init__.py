"""Cross-domain contract tests for the Python SDK handler/ subtree.

These tests verify that the Python SDK's 6 abstract handler bases and 11
concrete handlers (4 atomic + 3 builtin + 4 typed) behave equivalently to
their Java SDK counterparts. The tests are intentionally tolerant of
missing modules: when one of the 4 dependency feature branches has not
yet landed on main, the affected test is skipped (not failed). Once all
4 dependency PRs merge, this PR's CI should turn fully green.
"""
