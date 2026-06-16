/**
 * §1.1 parity guard: assert the TS constants deep-equal the YAML source of truth.
 * Uses a tiny hand-rolled parser for the simple `key:\n  - value` list structure
 * in sdk-shared-constants.yaml (NO yaml dependency — zero runtime deps).
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import {
  SUPPORTED_SCHEMA_VERSIONS,
  WORKER_RUNTIME_STATES,
  SENSITIVE_KEYWORDS,
  TASK_STATUSES,
} from "../src/constants.ts";

const YAML_PATH = new URL(
  "../../docs/api/sdk-shared-constants.yaml",
  import.meta.url,
);

/**
 * Parse only top-level `key:` blocks whose value is a YAML block list of scalars:
 *
 *   key:
 *     - a
 *     - b
 *
 * Returns a map of key -> string[]. Comments (#...) and blank lines are skipped.
 * Inline empty lists (`key: []`) parse to []. Scalar values are ignored.
 */
function parseSimpleYamlLists(text: string): Map<string, string[]> {
  const result = new Map<string, string[]>();
  const lines = text.split(/\r?\n/);
  let currentKey: string | null = null;

  for (const raw of lines) {
    // strip trailing comments only when not inside a quoted value (values here are plain)
    const line = raw.replace(/\s+#.*$/, "");
    if (line.trim() === "" || line.trim().startsWith("#")) {
      continue;
    }

    const listItem = /^\s+-\s+(.+?)\s*$/.exec(line);
    if (listItem && currentKey) {
      result.get(currentKey)!.push(unquote(listItem[1]));
      continue;
    }

    const topKey = /^([A-Za-z_][\w-]*):\s*(.*)$/.exec(line);
    if (topKey) {
      const key = topKey[1];
      const inlineValue = topKey[2].trim();
      if (inlineValue === "" ) {
        // block — list items (if any) follow on subsequent lines
        currentKey = key;
        result.set(key, []);
      } else if (inlineValue === "[]") {
        currentKey = null;
        result.set(key, []);
      } else {
        // scalar value (e.g. `version: 1`) — not a list, ignore
        currentKey = null;
      }
      continue;
    }

    // any other top-level / unindented content ends the current list
    if (!/^\s/.test(line)) {
      currentKey = null;
    }
  }
  return result;
}

function unquote(s: string): string {
  if (
    (s.startsWith('"') && s.endsWith('"')) ||
    (s.startsWith("'") && s.endsWith("'"))
  ) {
    return s.slice(1, -1);
  }
  return s;
}

const yamlText = readFileSync(YAML_PATH, "utf8");
const lists = parseSimpleYamlLists(yamlText);

test("schema_versions_supported parity", () => {
  assert.deepEqual(
    [...SUPPORTED_SCHEMA_VERSIONS],
    lists.get("schema_versions_supported"),
  );
});

test("worker_runtime_states parity", () => {
  assert.deepEqual(
    [...WORKER_RUNTIME_STATES],
    lists.get("worker_runtime_states"),
  );
});

test("sensitive_keywords parity", () => {
  assert.deepEqual([...SENSITIVE_KEYWORDS], lists.get("sensitive_keywords"));
});

test("task_statuses parity", () => {
  assert.deepEqual([...TASK_STATUSES], lists.get("task_statuses"));
});
