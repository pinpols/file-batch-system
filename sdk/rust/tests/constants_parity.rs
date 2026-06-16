//! §1.1 parity guard: assert the Rust constants equal the YAML source of truth.
//!
//! Uses a tiny hand-rolled parser for the simple `key:\n  - value` list
//! structure in `sdk-shared-constants.yaml` (NO yaml dependency — zero deps).

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

use batch_worker_sdk::constants::{
    SENSITIVE_KEYWORDS, SUPPORTED_SCHEMA_VERSIONS, TASK_STATUSES, WORKER_RUNTIME_STATES,
};

fn yaml_path() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../docs/api/sdk-shared-constants.yaml")
}

/// Strip an unquoted trailing `# comment` (preceded by whitespace) from a line.
fn strip_comment(line: &str) -> String {
    let bytes = line.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'#' && i > 0 && (bytes[i - 1] == b' ' || bytes[i - 1] == b'\t') {
            return line[..i].to_string();
        }
        i += 1;
    }
    line.to_string()
}

fn unquote(s: &str) -> String {
    let b = s.as_bytes();
    if b.len() >= 2
        && ((b[0] == b'"' && b[b.len() - 1] == b'"') || (b[0] == b'\'' && b[b.len() - 1] == b'\''))
    {
        return s[1..s.len() - 1].to_string();
    }
    s.to_string()
}

/// Parse only top-level `key:` blocks whose value is a YAML block list of
/// scalars. Inline `key: []` -> empty list; scalar values are ignored.
fn parse_simple_yaml_lists(text: &str) -> BTreeMap<String, Vec<String>> {
    let mut result: BTreeMap<String, Vec<String>> = BTreeMap::new();
    let mut current_key: Option<String> = None;

    for raw in text.lines() {
        let line = strip_comment(raw.trim_end_matches('\r'));
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with('#') {
            continue;
        }

        // list item: starts with whitespace then `- value`
        let is_indented = line.starts_with(' ') || line.starts_with('\t');
        if is_indented {
            let t = line.trim_start();
            if let Some(rest) = t.strip_prefix("- ") {
                if let Some(key) = &current_key {
                    result
                        .entry(key.clone())
                        .or_default()
                        .push(unquote(rest.trim()));
                }
                continue;
            }
            if t == "-" {
                continue;
            }
            // some other indented content; ignore
            continue;
        }

        // top-level `key:` or `key: value`
        if let Some(colon) = line.find(':') {
            let key = line[..colon].trim().to_string();
            let inline = line[colon + 1..].trim();
            match inline {
                "" => {
                    current_key = Some(key.clone());
                    result.entry(key).or_default();
                }
                "[]" => {
                    current_key = None;
                    result.insert(key, Vec::new());
                }
                _ => {
                    current_key = None; // scalar value, ignore
                }
            }
            continue;
        }

        // any other unindented content ends the current list
        current_key = None;
    }
    result
}

fn load_yaml_lists() -> BTreeMap<String, Vec<String>> {
    let path = yaml_path();
    let data = fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("read {}: {e}", path.display()));
    parse_simple_yaml_lists(&data)
}

#[test]
fn constants_parity() {
    let lists = load_yaml_lists();

    let cases: [(&str, &[&str]); 4] = [
        ("schema_versions_supported", SUPPORTED_SCHEMA_VERSIONS),
        ("worker_runtime_states", WORKER_RUNTIME_STATES),
        ("sensitive_keywords", SENSITIVE_KEYWORDS),
        ("task_statuses", TASK_STATUSES),
    ];

    for (key, got) in cases {
        let want = lists
            .get(key)
            .unwrap_or_else(|| panic!("YAML missing list key {key:?}"));
        let got_vec: Vec<String> = got.iter().map(|s| s.to_string()).collect();
        assert_eq!(
            &got_vec, want,
            "{key} parity: Rust={got_vec:?} YAML={want:?}"
        );
    }
}
