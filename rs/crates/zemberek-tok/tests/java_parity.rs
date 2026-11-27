use std::fs;
use std::path::PathBuf;
use zemberek_tok::{
    reconstruct_text, span_tokenize, TokenType, TurkishDetokenizer, TurkishSentenceExtractor,
    TurkishTokenizer,
};

fn unesc(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut it = s.chars();
    while let Some(c) = it.next() {
        if c == '\\' {
            match it.next() {
                Some('t') => out.push('\t'),
                Some('n') => out.push('\n'),
                Some('r') => out.push('\r'),
                Some('\\') => out.push('\\'),
                Some(other) => {
                    out.push('\\');
                    out.push(other);
                }
                None => out.push('\\'),
            }
        } else {
            out.push(c);
        }
    }
    out
}

#[test]
fn java_parity_tokenizer_and_sentence_extractor() {
    let manifest_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let tsv_path = manifest_dir.join("../../testdata/step1_tok_parity.tsv");
    let content = fs::read_to_string(&tsv_path).expect("read step1_tok_parity.tsv");

    let mut seg_checked = 0usize;
    let mut tok_paragraphs = 0usize;
    let mut total_tokens = 0usize;
    let mut exact_token_matches = 0usize;
    let mut span_checked = 0usize;

    for (line_no, raw_line) in content.lines().enumerate() {
        let cols: Vec<&str> = raw_line.split('\t').collect();
        match cols[0] {
            "SEG" => {
                let p = unesc(cols[1]);
                let exp_def = unesc(cols[2]);
                let exp_noq = unesc(cols[3]);

                let got_def = TurkishSentenceExtractor::DEFAULT
                    .from_paragraph(&p)
                    .join("\u{001F}");
                let got_noq = TurkishSentenceExtractor::DO_NOT_SPLIT_IN_DOUBLE_QUOTES
                    .from_paragraph(&p)
                    .join("\u{001F}");

                assert_eq!(
                    got_def,
                    exp_def,
                    "SentenceExtractor DEFAULT mismatch at line {}:\ninput={p:?}",
                    line_no + 1
                );
                assert_eq!(
                    got_noq,
                    exp_noq,
                    "SentenceExtractor NO_QUOTE mismatch at line {}:\ninput={p:?}",
                    line_no + 1
                );

                // Verify UTF-16 sentence offsets slice UTF-16 buffer identically
                let utf16_vec: Vec<u16> = p.encode_utf16().collect();
                for sp in TurkishSentenceExtractor::DEFAULT.from_paragraph_spans(&p) {
                    let from_u16 = String::from_utf16(&utf16_vec[sp.utf16_start..sp.utf16_end])
                        .expect("valid UTF-16 sentence slice");
                    assert_eq!(from_u16, sp.text(&p));
                }
                seg_checked += 1;
            }
            "TOK" => {
                let p = unesc(cols[1]);
                let exp_tok_blob = unesc(cols[2]);
                let exp_detok = unesc(cols[3]);

                let got_toks = TurkishTokenizer::DEFAULT.tokenize(&p);
                let exp_items: Vec<&str> = if exp_tok_blob.is_empty() {
                    Vec::new()
                } else {
                    exp_tok_blob.split('\u{001F}').collect()
                };

                // Verify UTF-16 slicing on every emitted Rust token
                let utf16_vec: Vec<u16> = p.encode_utf16().collect();
                for t in &got_toks {
                    let sliced = String::from_utf16(&utf16_vec[t.utf16_start..t.utf16_end])
                        .expect("valid UTF-16 token slice");
                    assert_eq!(sliced, t.content, "UTF-16 span mismatch in {p:?}");
                }

                total_tokens += exp_items.len();
                if got_toks.len() == exp_items.len() {
                    let mut all_ok = true;
                    for (gt, &et) in got_toks.iter().zip(exp_items.iter()) {
                        let mut parts = et.splitn(4, '\u{001E}');
                        let ty_ord: u8 = parts.next().unwrap().parse().unwrap();
                        let st: usize = parts.next().unwrap().parse().unwrap();
                        let en: usize = parts.next().unwrap().parse().unwrap();
                        let txt = parts.next().unwrap();
                        if gt.token_type as u8 == ty_ord
                            && gt.java_start() == st
                            && gt.java_end() == en
                            && gt.content == txt
                        {
                            exact_token_matches += 1;
                        } else {
                            all_ok = false;
                        }
                    }
                    if !all_ok {
                        panic!(
                            "Token detail mismatch at line {}:\ninput={p:?}\ngot={got_toks:?}\nexp={exp_items:?}",
                            line_no + 1
                        );
                    }
                } else {
                    panic!(
                        "Token count mismatch at line {}:\ninput={p:?}\ngot={got_toks:?}\nexp={exp_items:?}",
                        line_no + 1
                    );
                }

                let got_detok = TurkishDetokenizer::detokenize_tokens(&got_toks);
                assert_eq!(
                    got_detok,
                    exp_detok,
                    "Detokenizer mismatch at line {}:\ninput={p:?}",
                    line_no + 1
                );
                tok_paragraphs += 1;
            }
            "SPAN" => {
                let p = unesc(cols[1]);
                let exp_all_blob = unesc(cols[2]);
                let exp_span_blob = unesc(cols[3]);
                let exp_recon = unesc(cols[4]);

                let got_all = TurkishTokenizer::ALL.tokenize(&p);
                let got_all_str = got_all
                    .iter()
                    .map(|t| {
                        format!(
                            "{}\u{001E}{}\u{001E}{}",
                            t.token_type as u8,
                            t.java_start(),
                            t.java_end()
                        )
                    })
                    .collect::<Vec<_>>()
                    .join("\u{001F}");
                assert_eq!(
                    got_all_str,
                    exp_all_blob,
                    "ALL tokenizer mismatch at line {}",
                    line_no + 1
                );
                assert_eq!(TurkishDetokenizer::detokenize_tokens(&got_all), p);

                let s_toks = span_tokenize(&p);
                let got_span_str = s_toks
                    .iter()
                    .map(|st| {
                        format!(
                            "{}\u{001E}{}\u{001E}{}\u{001E}{}\u{001E}{}",
                            st.shape as u8,
                            u8::from(st.adjacent_to_prev),
                            u8::from(st.is_random_laugh()),
                            st.lower,
                            st.ascii
                        )
                    })
                    .collect::<Vec<_>>()
                    .join("\u{001F}");
                assert_eq!(
                    got_span_str,
                    exp_span_blob,
                    "SpanToken mismatch at line {}",
                    line_no + 1
                );

                let id_path: Vec<(usize, usize, &str)> = s_toks
                    .iter()
                    .enumerate()
                    .map(|(i, st)| (i, i + 1, st.surface.as_str()))
                    .collect();
                let got_recon = reconstruct_text(&s_toks, &id_path);
                assert_eq!(
                    got_recon,
                    exp_recon,
                    "reconstruct_text mismatch at line {}",
                    line_no + 1
                );
                span_checked += 1;
            }
            other => panic!("Unknown record type: {other}"),
        }
    }

    assert!(seg_checked >= 2000);
    assert!(tok_paragraphs >= 2000);
    assert_eq!(exact_token_matches, total_tokens);
    assert!(span_checked >= 250);
}

#[test]
fn document_segmentation_and_builder_filtering() {
    let doc = "Merhaba!\n Bugün 2. köprü Fsm.'de trafik vardı.değil mi?\n\rAli gel.";
    let sents = TurkishSentenceExtractor::DEFAULT.from_document(doc);
    assert_eq!(
        sents,
        vec![
            "Merhaba!",
            "Bugün 2. köprü Fsm.'de trafik vardı.",
            "değil mi?",
            "Ali gel."
        ]
    );

    let utf16_doc: Vec<u16> = doc.encode_utf16().collect();
    for sp in TurkishSentenceExtractor::DEFAULT.from_document_spans(doc) {
        let sliced = String::from_utf16(&utf16_doc[sp.utf16_start..sp.utf16_end]).unwrap();
        assert_eq!(sliced, sp.text(doc));
    }

    let only_numbers = TurkishTokenizer::builder()
        .ignore_all()
        .accept_types(&[TokenType::Number])
        .build();
    assert_eq!(
        only_numbers.tokenize_to_strings("www.foo.bar 12,4'ü a@a.v ; ^% 2 adf 12 \r \n "),
        vec!["12,4'ü", "2", "12"]
    );
}
