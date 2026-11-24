import json
import os
from collections import Counter

files = [
    ('golden1000', 'data/ambiguity/precision_test_golden1000_th20k_vs_33k.json'),
    ('golden2500_orig', 'data/ambiguity/precision_test_golden2500_th20k_vs_33k.json'),
    ('golden2500_fresh', 'data/ambiguity/precision_test_goldenfresh2500_th20k_vs_33k.json')
]

out_jsonl = 'data/ambiguity/golden_unresolved_error_sentences.jsonl'
out_summary = 'data/ambiguity/golden_unresolved_error_summary.json'

os.makedirs('data/ambiguity', exist_ok=True)

error_sentences = []
total_sentences_scanned = 0
total_tokens_scanned = 0
total_ambig_tokens_scanned = 0
total_error_tokens = 0

word_errors = Counter()
signature_errors = Counter()

for dataset_name, fp in files:
    with open(fp) as f:
        d = json.load(f)
    
    total_sentences_scanned += d['total_sentences']
    total_tokens_scanned += d['total_tokens']
    total_ambig_tokens_scanned += d['ambiguous_tokens']
    
    for s in d['sentences']:
        errors_in_sent = []
        tokens = s['tokens']
        for i, tok in enumerate(tokens):
            if not tok.get('is_ambiguous'):
                continue
            gt = tok.get('gemini_selected_key')
            pred = tok.get('trained_selected_key')
            if gt and pred and gt != pred:
                left = ' '.join(t['surface'] for t in tokens[max(0, i-3):i])
                right = ' '.join(t['surface'] for t in tokens[i+1:min(len(tokens), i+4)])
                
                # Abstract signature
                gt_morph = gt.split('] ')[1] if '] ' in gt else gt
                pred_morph = pred.split('] ')[1] if '] ' in pred else pred
                gt_sig = gt_morph.split(':', 1)[1] if ':' in gt_morph else gt_morph
                pred_sig = pred_morph.split(':', 1)[1] if ':' in pred_morph else pred_morph
                same_root = (gt.split('[')[1].split(':')[0] == pred.split('[')[1].split(':')[0]) if ('[' in gt and '[' in pred) else False
                sig_key = f"{'SameRoot' if same_root else 'DiffRoot'} | GT:{gt_sig} <--> PRED:{pred_sig}"
                
                signature_errors[sig_key] += 1
                word_errors[tok['surface']] += 1
                
                errors_in_sent.append({
                    'token_index': i,
                    'surface': tok['surface'],
                    'left_context': left,
                    'right_context': right,
                    'ground_truth': gt,
                    'predicted': pred,
                    'candidate_count': tok.get('candidate_count'),
                    'signature': sig_key
                })
        
        if errors_in_sent:
            error_sentences.append({
                'dataset': dataset_name,
                'sentence_id': s.get('sentence_id'),
                'text': s.get('text'),
                'total_tokens': len(tokens),
                'error_count': len(errors_in_sent),
                'errors': errors_in_sent,
                'tokens': tokens
            })
            total_error_tokens += len(errors_in_sent)

# Write jsonl
with open(out_jsonl, 'w', encoding='utf-8') as f:
    for rec in error_sentences:
        f.write(json.dumps(rec, ensure_ascii=False) + '\n')

# Write summary
summary = {
    'total_sentences_scanned': total_sentences_scanned,
    'total_tokens_scanned': total_tokens_scanned,
    'total_ambiguous_tokens_scanned': total_ambig_tokens_scanned,
    'unresolved_sentences_count': len(error_sentences),
    'unresolved_sentence_rate': len(error_sentences) / total_sentences_scanned,
    'total_error_tokens': total_error_tokens,
    'token_error_rate': total_error_tokens / total_tokens_scanned,
    'ambiguous_token_error_rate': total_error_tokens / total_ambig_tokens_scanned,
    'unique_words_with_errors': len(word_errors),
    'unique_signatures_count': len(signature_errors),
    'top_words': word_errors.most_common(50),
    'top_signatures': signature_errors.most_common(50)
}

with open(out_summary, 'w', encoding='utf-8') as f:
    json.dump(summary, f, ensure_ascii=False, indent=2)

print(f"Extraction Complete!")
print(f"Saved {len(error_sentences):,} sentences with >=1 error to: {out_jsonl}")
print(f"Saved summary report to: {out_summary}")
print(f"Total error tokens: {total_error_tokens:,}")
