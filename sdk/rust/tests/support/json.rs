//! Minimal std-only JSON value parser for the conformance + parity test harness.
//!
//! Correct for the contract fixtures (well-formed, UTF-8, standard escapes only).
//! Produces a [`Json`] enum the test runner navigates. NOT part of the library
//! crate — this lives under `tests/` so the published SDK stays dependency-free
//! and parser-free.

#![allow(dead_code)]

use std::collections::BTreeMap;

#[derive(Debug, Clone, PartialEq)]
pub enum Json {
    Null,
    Bool(bool),
    Num(f64),
    Str(String),
    Arr(Vec<Json>),
    Obj(BTreeMap<String, Json>),
}

impl Json {
    pub fn as_str(&self) -> Option<&str> {
        match self {
            Json::Str(s) => Some(s),
            _ => None,
        }
    }
    pub fn as_bool(&self) -> Option<bool> {
        match self {
            Json::Bool(b) => Some(*b),
            _ => None,
        }
    }
    pub fn as_num(&self) -> Option<f64> {
        match self {
            Json::Num(n) => Some(*n),
            _ => None,
        }
    }
    pub fn as_array(&self) -> Option<&Vec<Json>> {
        match self {
            Json::Arr(a) => Some(a),
            _ => None,
        }
    }
    pub fn as_object(&self) -> Option<&BTreeMap<String, Json>> {
        match self {
            Json::Obj(o) => Some(o),
            _ => None,
        }
    }
    pub fn is_null(&self) -> bool {
        matches!(self, Json::Null)
    }
    /// Lookup a key in an object; returns `None` for non-objects or missing keys.
    pub fn get(&self, key: &str) -> Option<&Json> {
        self.as_object().and_then(|o| o.get(key))
    }
}

pub fn parse(input: &str) -> Result<Json, String> {
    let mut p = Parser {
        chars: input.chars().collect(),
        pos: 0,
    };
    p.skip_ws();
    let v = p.parse_value()?;
    p.skip_ws();
    if p.pos != p.chars.len() {
        return Err(format!("trailing input at position {}", p.pos));
    }
    Ok(v)
}

struct Parser {
    chars: Vec<char>,
    pos: usize,
}

impl Parser {
    fn peek(&self) -> Option<char> {
        self.chars.get(self.pos).copied()
    }

    fn bump(&mut self) -> Option<char> {
        let c = self.chars.get(self.pos).copied();
        if c.is_some() {
            self.pos += 1;
        }
        c
    }

    fn skip_ws(&mut self) {
        while let Some(c) = self.peek() {
            if c == ' ' || c == '\t' || c == '\n' || c == '\r' {
                self.pos += 1;
            } else {
                break;
            }
        }
    }

    fn parse_value(&mut self) -> Result<Json, String> {
        self.skip_ws();
        match self.peek() {
            Some('{') => self.parse_object(),
            Some('[') => self.parse_array(),
            Some('"') => Ok(Json::Str(self.parse_string()?)),
            Some('t') | Some('f') => self.parse_bool(),
            Some('n') => self.parse_null(),
            Some(c) if c == '-' || c.is_ascii_digit() => self.parse_number(),
            other => Err(format!("unexpected token {other:?} at position {}", self.pos)),
        }
    }

    fn expect(&mut self, c: char) -> Result<(), String> {
        if self.peek() == Some(c) {
            self.pos += 1;
            Ok(())
        } else {
            Err(format!("expected {c:?} at position {}", self.pos))
        }
    }

    fn parse_object(&mut self) -> Result<Json, String> {
        self.expect('{')?;
        let mut map = BTreeMap::new();
        self.skip_ws();
        if self.peek() == Some('}') {
            self.pos += 1;
            return Ok(Json::Obj(map));
        }
        loop {
            self.skip_ws();
            let key = self.parse_string()?;
            self.skip_ws();
            self.expect(':')?;
            let value = self.parse_value()?;
            map.insert(key, value);
            self.skip_ws();
            match self.bump() {
                Some(',') => continue,
                Some('}') => break,
                other => return Err(format!("expected ',' or '}}' got {other:?}")),
            }
        }
        Ok(Json::Obj(map))
    }

    fn parse_array(&mut self) -> Result<Json, String> {
        self.expect('[')?;
        let mut arr = Vec::new();
        self.skip_ws();
        if self.peek() == Some(']') {
            self.pos += 1;
            return Ok(Json::Arr(arr));
        }
        loop {
            let value = self.parse_value()?;
            arr.push(value);
            self.skip_ws();
            match self.bump() {
                Some(',') => continue,
                Some(']') => break,
                other => return Err(format!("expected ',' or ']' got {other:?}")),
            }
        }
        Ok(Json::Arr(arr))
    }

    fn parse_string(&mut self) -> Result<String, String> {
        self.expect('"')?;
        let mut s = String::new();
        loop {
            match self.bump() {
                None => return Err("unterminated string".to_string()),
                Some('"') => break,
                Some('\\') => {
                    let esc = self.bump().ok_or("unterminated escape")?;
                    match esc {
                        '"' => s.push('"'),
                        '\\' => s.push('\\'),
                        '/' => s.push('/'),
                        'b' => s.push('\u{0008}'),
                        'f' => s.push('\u{000C}'),
                        'n' => s.push('\n'),
                        'r' => s.push('\r'),
                        't' => s.push('\t'),
                        'u' => {
                            let cp = self.parse_hex4()?;
                            if (0xD800..=0xDBFF).contains(&cp) {
                                // high surrogate — expect a low surrogate next
                                self.expect('\\')?;
                                self.expect('u')?;
                                let low = self.parse_hex4()?;
                                if !(0xDC00..=0xDFFF).contains(&low) {
                                    return Err("invalid low surrogate".to_string());
                                }
                                let c = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                                s.push(
                                    char::from_u32(c).ok_or("invalid surrogate pair")?,
                                );
                            } else {
                                s.push(char::from_u32(cp).ok_or("invalid unicode escape")?);
                            }
                        }
                        other => return Err(format!("invalid escape \\{other}")),
                    }
                }
                Some(c) => s.push(c),
            }
        }
        Ok(s)
    }

    fn parse_hex4(&mut self) -> Result<u32, String> {
        let mut v = 0u32;
        for _ in 0..4 {
            let c = self.bump().ok_or("unterminated unicode escape")?;
            let d = c.to_digit(16).ok_or("invalid hex digit")?;
            v = v * 16 + d;
        }
        Ok(v)
    }

    fn parse_bool(&mut self) -> Result<Json, String> {
        if self.match_literal("true") {
            Ok(Json::Bool(true))
        } else if self.match_literal("false") {
            Ok(Json::Bool(false))
        } else {
            Err(format!("invalid literal at position {}", self.pos))
        }
    }

    fn parse_null(&mut self) -> Result<Json, String> {
        if self.match_literal("null") {
            Ok(Json::Null)
        } else {
            Err(format!("invalid literal at position {}", self.pos))
        }
    }

    fn match_literal(&mut self, lit: &str) -> bool {
        let lit_chars: Vec<char> = lit.chars().collect();
        if self.pos + lit_chars.len() > self.chars.len() {
            return false;
        }
        if self.chars[self.pos..self.pos + lit_chars.len()] == lit_chars[..] {
            self.pos += lit_chars.len();
            true
        } else {
            false
        }
    }

    fn parse_number(&mut self) -> Result<Json, String> {
        let start = self.pos;
        if self.peek() == Some('-') {
            self.pos += 1;
        }
        while let Some(c) = self.peek() {
            if c.is_ascii_digit() || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-' {
                self.pos += 1;
            } else {
                break;
            }
        }
        let num_str: String = self.chars[start..self.pos].iter().collect();
        num_str
            .parse::<f64>()
            .map(Json::Num)
            .map_err(|_| format!("invalid number {num_str:?}"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_nested_structure() {
        let v = parse(
            r#"{ "a": 1, "b": [true, null, "x\n"], "c": {"d": -2.5e1}, "e": false }"#,
        )
        .unwrap();
        assert_eq!(v.get("a").and_then(Json::as_num), Some(1.0));
        assert_eq!(v.get("e").and_then(Json::as_bool), Some(false));
        let b = v.get("b").and_then(Json::as_array).unwrap();
        assert_eq!(b[0], Json::Bool(true));
        assert_eq!(b[1], Json::Null);
        assert_eq!(b[2], Json::Str("x\n".to_string()));
        assert_eq!(
            v.get("c").and_then(|c| c.get("d")).and_then(Json::as_num),
            Some(-25.0)
        );
    }

    #[test]
    fn parses_empty_containers() {
        assert_eq!(parse("[]").unwrap(), Json::Arr(vec![]));
        assert_eq!(parse("{}").unwrap(), Json::Obj(BTreeMap::new()));
    }
}
