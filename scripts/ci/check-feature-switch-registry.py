#!/usr/bin/env python3
"""Validate the feature-switch registry as a strict CI contract."""

from __future__ import annotations

import re
import sys
from collections import Counter

from feature_switch_registry import load_feature_switches

KEY_PATTERN = re.compile(r"^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$")
ENV_PATTERN = re.compile(r"^BATCH_[A-Z0-9_]+$")


def main() -> int:
    switches = load_feature_switches()
    errors: list[str] = []
    keys = Counter(item.key for item in switches)
    envs = Counter(env for item in switches for env in item.env)

    for key, count in sorted(keys.items()):
        if count > 1:
            errors.append(f"duplicate switch key: {key}")
        if not KEY_PATTERN.fullmatch(key):
            errors.append(f"invalid switch key: {key}")

    for env, count in sorted(envs.items()):
        if count > 1:
            errors.append(f"environment variable registered more than once: {env}")
        if not ENV_PATTERN.fullmatch(env):
            errors.append(f"invalid environment variable: {env}")

    for item in switches:
        if not item.module.strip():
            errors.append(f"{item.key}: module must not be blank")
        if item.helm_required and not (item.helm_env or item.env):
            errors.append(f"{item.key}: helmRequired=true but no Helm environment variable")

    if errors:
        print("❌ feature switch registry validation failed:")
        for error in errors:
            print(f"  - {error}")
        return 1

    print(f"✅ feature switch registry valid: {len(switches)} switches, {len(envs)} environment variables")
    return 0


if __name__ == "__main__":
    sys.exit(main())
