//! Sensitive-data validation (§1.8 — credential-leak prevention).
//!
//! Mirrors the Java SDK `SensitiveDataValidator` (Lane C) and the TS/Go
//! phase-2 `sensitive` module: scan the **keys** of a register body or dispatch
//! parameters; if a key contains a sensitive keyword
//! ([`crate::constants::SENSITIVE_KEYWORDS`]) **and** carries a non-empty value,
//! reject — credentials must travel via env / secret, never in payload.
//!
//! Reject semantics by call site (§1.8):
//! * register-time  → fail-fast (worker refuses to start)
//! * parameters-time → fail the task, report `errorCode = SECURITY_REJECTED`
//!
//! The validator only flags non-empty values: a sensitive key with an empty
//! string is the *correct* shape (a placeholder for an env-injected secret).

use crate::constants::SENSITIVE_KEYWORDS;

/// Outcome of a sensitive scan.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Validation {
    /// No sensitive key carried a value.
    Ok,
    /// A sensitive key carried a non-empty value; carries the offending keys.
    Rejected { offending_keys: Vec<String> },
}

impl Validation {
    pub fn is_ok(&self) -> bool {
        matches!(self, Validation::Ok)
    }
    pub fn is_rejected(&self) -> bool {
        matches!(self, Validation::Rejected { .. })
    }
}

/// Scans payload keys for credential leaks. A tenant deny-list (extra keywords)
/// can be added via [`SensitiveValidator::with_deny_list`].
#[derive(Debug, Clone, Default)]
pub struct SensitiveValidator {
    /// Extra tenant-supplied keywords (lower-cased), appended to the built-ins.
    deny_list: Vec<String>,
}

impl SensitiveValidator {
    pub fn new() -> Self {
        Self::default()
    }

    /// Add tenant-specific sensitive keywords (deny-list hook, §1.8).
    /// Keywords are matched case-insensitively as substrings of the key.
    pub fn with_deny_list(mut self, extra: &[&str]) -> Self {
        for kw in extra {
            self.deny_list.push(kw.to_lowercase());
        }
        self
    }

    /// True if `key` (case-insensitively) contains any built-in or deny-listed
    /// sensitive keyword.
    pub fn is_sensitive_key(&self, key: &str) -> bool {
        let lower = key.to_lowercase();
        SENSITIVE_KEYWORDS.iter().any(|kw| lower.contains(kw))
            || self.deny_list.iter().any(|kw| lower.contains(kw.as_str()))
    }

    /// Validate a set of (key, value) entries. Any sensitive key whose value is
    /// non-empty (after trimming) is an offence.
    ///
    /// Generic over the iterator so callers can pass a `BTreeMap`, `HashMap`,
    /// or a slice of pairs without allocating.
    pub fn validate<'a, I>(&self, entries: I) -> Validation
    where
        I: IntoIterator<Item = (&'a str, &'a str)>,
    {
        let mut offending = Vec::new();
        for (key, value) in entries {
            if self.is_sensitive_key(key) && !value.trim().is_empty() {
                offending.push(key.to_string());
            }
        }
        if offending.is_empty() {
            Validation::Ok
        } else {
            Validation::Rejected {
                offending_keys: offending,
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;

    #[test]
    fn catches_credentials_with_values() {
        let v = SensitiveValidator::new();
        let mut params = BTreeMap::new();
        params.insert("password".to_string(), "hunter2".to_string());
        params.insert("region".to_string(), "us-east-1".to_string());
        let result = v.validate(params.iter().map(|(k, val)| (k.as_str(), val.as_str())));
        assert!(result.is_rejected());
        if let Validation::Rejected { offending_keys } = result {
            assert_eq!(offending_keys, vec!["password".to_string()]);
        }
    }

    #[test]
    fn empty_sensitive_value_is_allowed() {
        // The correct shape: a placeholder for an env-injected secret.
        let v = SensitiveValidator::new();
        let entries = [("apiKey", ""), ("client_secret", "   "), ("name", "svc")];
        assert!(v.validate(entries).is_ok());
    }

    #[test]
    fn matches_camel_and_snake_variants() {
        let v = SensitiveValidator::new();
        assert!(v.is_sensitive_key("apiKey"));
        assert!(v.is_sensitive_key("API_KEY"));
        assert!(v.is_sensitive_key("dbPassword"));
        assert!(v.is_sensitive_key("accessKeyId"));
        assert!(!v.is_sensitive_key("username"));
    }

    #[test]
    fn deny_list_hook_extends_keywords() {
        let v = SensitiveValidator::new().with_deny_list(&["pin"]);
        assert!(v.is_sensitive_key("userPin"));
        let entries = [("userPin", "1234")];
        assert!(v.validate(entries).is_rejected());
    }

    #[test]
    fn non_sensitive_payload_passes() {
        let v = SensitiveValidator::new();
        let entries = [("tenantId", "tenant-a"), ("limit", "100")];
        assert!(v.validate(entries).is_ok());
    }
}
