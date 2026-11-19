//! Natural ("human") ordering for page names. A total order, so sorting is deterministic.

use std::cmp::Ordering;

/// One run of either ASCII digits or anything else.
#[derive(Clone, Copy)]
struct Chunk<'a> {
    text: &'a str,
    is_digits: bool,
}

/// Splits off the first chunk of `s`, returning it and the remainder. `s` must be non-empty.
fn next_chunk(s: &str) -> (Chunk<'_>, &str) {
    let is_digits = s.as_bytes()[0].is_ascii_digit();
    let end = s
        .char_indices()
        .find(|(_, c)| c.is_ascii_digit() != is_digits)
        .map_or(s.len(), |(i, _)| i);
    let (text, rest) = s.split_at(end);
    (Chunk { text, is_digits }, rest)
}

fn strip_zeros(s: &str) -> &str {
    s.trim_start_matches('0')
}

fn cmp_chunk(a: Chunk, b: Chunk) -> Ordering {
    match (a.is_digits, b.is_digits) {
        (true, true) => {
            // By value without parsing: shorter is smaller, then lexicographic.
            let (a, b) = (strip_zeros(a.text), strip_zeros(b.text));
            a.len().cmp(&b.len()).then_with(|| a.cmp(b))
        }
        (false, false) => a
            .text
            .chars()
            .flat_map(char::to_lowercase)
            .cmp(b.text.chars().flat_map(char::to_lowercase)),
        (true, false) => Ordering::Less,
        (false, true) => Ordering::Greater,
    }
}

/// Compares one path component: digit runs by numeric value, other runs case-insensitively.
///
/// Ties are broken by fewer leading zeros, then raw bytes, so `Equal` means identical strings.
pub fn natural_cmp(a: &str, b: &str) -> Ordering {
    let (mut ra, mut rb) = (a, b);
    while !ra.is_empty() && !rb.is_empty() {
        let (ca, rest_a) = next_chunk(ra);
        let (cb, rest_b) = next_chunk(rb);
        match cmp_chunk(ca, cb) {
            Ordering::Equal => {}
            other => return other,
        }
        ra = rest_a;
        rb = rest_b;
    }
    // The shorter chunk sequence is smaller.
    match (ra.is_empty(), rb.is_empty()) {
        (true, false) => return Ordering::Less,
        (false, true) => return Ordering::Greater,
        _ => {}
    }
    // Same chunk sequence: fewer leading zeros first.
    let (mut ra, mut rb) = (a, b);
    while !ra.is_empty() {
        let (ca, rest_a) = next_chunk(ra);
        let (cb, rest_b) = next_chunk(rb);
        if ca.is_digits {
            let zeros_a = ca.text.len() - strip_zeros(ca.text).len();
            let zeros_b = cb.text.len() - strip_zeros(cb.text).len();
            match zeros_a.cmp(&zeros_b) {
                Ordering::Equal => {}
                other => return other,
            }
        }
        ra = rest_a;
        rb = rest_b;
    }
    a.as_bytes().cmp(b.as_bytes())
}

/// Compares paths component-wise (split on `/` and `\`) with [`natural_cmp`].
/// A proper prefix is smaller; the final tie-break is the raw bytes of the whole string.
pub fn natural_path_cmp(a: &str, b: &str) -> Ordering {
    let is_sep = |c: char| c == '/' || c == '\\';
    let mut ca = a.split(is_sep);
    let mut cb = b.split(is_sep);
    loop {
        match (ca.next(), cb.next()) {
            (None, None) => break,
            (None, Some(_)) => return Ordering::Less,
            (Some(_), None) => return Ordering::Greater,
            (Some(x), Some(y)) => match natural_cmp(x, y) {
                Ordering::Equal => {}
                other => return other,
            },
        }
    }
    a.as_bytes().cmp(b.as_bytes())
}

#[cfg(test)]
mod tests {
    use super::*;
    use proptest::prelude::*;
    use std::cmp::Ordering;

    fn sorted_names(mut v: Vec<&str>) -> Vec<&str> {
        v.sort_by(|a, b| natural_cmp(a, b));
        v
    }

    #[test]
    fn numeric_runs_compare_by_value() {
        assert_eq!(
            sorted_names(vec!["10.jpg", "2.jpg", "1.jpg"]),
            vec!["1.jpg", "2.jpg", "10.jpg"]
        );
    }

    #[test]
    fn text_is_case_insensitive() {
        assert_eq!(natural_cmp("a2", "B1"), Ordering::Less);
    }

    #[test]
    fn fewer_leading_zeros_sorts_first_but_not_equal() {
        assert_eq!(natural_cmp("page1", "page01"), Ordering::Less);
        assert_ne!(natural_cmp("page1", "page01"), Ordering::Equal);
    }

    #[test]
    fn huge_digit_runs_do_not_overflow() {
        assert_eq!(
            natural_cmp("x99999999999999999999999", "x100000000000000000000000"),
            Ordering::Less
        );
    }

    #[test]
    fn cjk_names_sort_numerically() {
        assert_eq!(natural_cmp("第3話.jpg", "第10話.jpg"), Ordering::Less);
    }

    #[test]
    fn paths_sort_component_wise() {
        let mut v = vec!["b/1.jpg", "a/10.jpg", "a b/1.jpg", "a/2.jpg"];
        v.sort_by(|a, b| natural_path_cmp(a, b));
        assert_eq!(v, vec!["a/2.jpg", "a/10.jpg", "a b/1.jpg", "b/1.jpg"]);
    }

    #[test]
    fn backslash_is_a_separator() {
        assert_eq!(natural_path_cmp("ch1\\2.jpg", "ch1/10.jpg"), Ordering::Less);
    }

    proptest! {
        #[test]
        fn natural_path_cmp_is_total_order(
            a in r"[0-9a-zA-Z/\\ 第話._-]{0,12}",
            b in r"[0-9a-zA-Z/\\ 第話._-]{0,12}",
            c in r"[0-9a-zA-Z/\\ 第話._-]{0,12}",
        ) {
            let ab = natural_path_cmp(&a, &b);
            let ba = natural_path_cmp(&b, &a);
            prop_assert_eq!(ab, ba.reverse());
            prop_assert_eq!(ab == Ordering::Equal, a == b);
            if natural_path_cmp(&a, &b) != Ordering::Greater
                && natural_path_cmp(&b, &c) != Ordering::Greater
            {
                prop_assert_ne!(natural_path_cmp(&a, &c), Ordering::Greater);
            }
        }
    }
}
