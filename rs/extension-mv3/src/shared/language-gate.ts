import type { ZemberekWasmClient } from "../wasm/zemberek-client";

const TURKISH_DIACRITIC_RE = /[çğıİöşüÇĞÖŞÜ]/;
const NON_TURKISH_LETTER_RE = /[äßéèêëñãåøæœÄÉÈÊËÑÅØÆŒ\u0400-\u04FF\u0370-\u03FF\u0600-\u06FF\u4E00-\u9FFF]/;
const ALPHA_TOKEN_RE = /\p{L}+(?:['’]\p{L}+)*/gu;

/**
 * High-frequency function words from English, German, French, and Spanish that
 * NEVER collide with valid Turkish words or ASCII-typed Turkish words
 * (carefully excluding bilingual homographs such as `in`, `on`, `at`, `an`, `is` [iş],
 * `us` [üs], `or` [ör], `once` [önce], `her`, `his`, `not`, `der`, `dem`, `sans` [şans],
 * `dans`, `para`, `son`, `ton`, `ben`, `sen`, `ne`, `ve`, `de`, `da`, `ki`, `mi`, `su`, `bu`).
 */
const UNAMBIGUOUS_FOREIGN_WORDS = new Set<string>([
  // English
  "the", "and", "for", "that", "this", "with", "from", "have", "has", "had",
  "are", "was", "were", "been", "being", "will", "would", "could", "should",
  "shall", "must", "might", "cannot", "can't", "don't", "doesn't", "didn't",
  "isn't", "aren't", "wasn't", "weren't", "won't", "wouldn't", "couldn't",
  "shouldn't", "please", "thank", "thanks", "hello", "welcome", "sorry",
  "about", "into", "onto", "upon", "within", "without", "through", "between",
  "among", "against", "during", "before", "after", "under", "above", "below",
  "because", "although", "though", "while", "where", "when", "what", "which",
  "whose", "whom", "their", "there", "these", "those", "they", "them", "your",
  "yours", "you", "you're", "you've", "you'll", "we're", "we've", "they're",
  "i'm", "i've", "i'll", "it's", "that's", "there's", "what's", "let's",
  "ourselves", "themselves", "yourself", "myself", "something", "anything",
  "nothing", "everything", "someone", "anyone", "everyone", "however",
  "therefore", "already", "always", "never", "often", "sometimes", "usually",
  "really", "very", "much", "many", "some", "other", "another", "such", "each",
  "every", "both", "either", "neither", "good", "morning", "afternoon",
  "evening", "today", "tomorrow", "yesterday", "meeting", "project", "update",
  "settings", "review", "request", "message", "example", "working", "looking",
  "testing", "following", "attached", "regards", "sincerely",
  // German
  "und", "oder", "aber", "nicht", "kein", "keine", "keinen", "keinem",
  "einer", "eine", "einem", "einen", "wir", "ihr", "wird", "werden", "wurde",
  "haben", "hatte", "über", "unter", "nach", "durch", "gegen", "ohne", "warum",
  "heute", "morgen", "immer", "bitte", "danke", "hallo", "willkommen",
  // French & Spanish
  "avec", "pour", "sous", "chez", "mais", "donc", "comme", "cette", "sont",
  "nous", "vous", "leur", "leurs", "bonjour", "merci", "como", "pero", "este",
  "esta", "estos", "estas", "todo", "todos", "hacer", "puede", "gracias", "hola",
]);

const ENGLISH_ORTHO_RE = /(?:^wh|tion$|sion$|ght$|ing$|ould$|ment$|ness$|ship$|able$|ible$|fully$)/;
const TURKISH_ASCII_CLUE_RE = /(?:lar$|ler$|dan$|den$|tan$|ten$|nin$|nun$|lik$|luk$|iyor|acak|ecek|icam$|icem$|ucam$|ucem$|misin|musun|iniz$|unuz$|imiz$|umuz$)/;

export function hasTurkishDiacritics(text: string): boolean {
  return TURKISH_DIACRITIC_RE.test(text);
}

/**
 * Fast synchronous pre-check usable in both Content Scripts and the Wasm Worker.
 * Returns `"tr"` when clearly Turkish, `"non-tr"` when clearly foreign, or `"uncertain"`
 * when ASCII text requires morphological / CLD3 verification.
 */
export function classifyTextOrthography(text: string): "tr" | "non-tr" | "uncertain" {
  if (!text || !text.trim()) return "uncertain";

  const rawTokens = text.match(ALPHA_TOKEN_RE);
  if (!rawTokens || rawTokens.length === 0) return "uncertain";

  const hasTrChars = TURKISH_DIACRITIC_RE.test(text);
  const hasForeignChars = NON_TURKISH_LETTER_RE.test(text);

  if (hasForeignChars && !hasTrChars) {
    return "non-tr";
  }

  let foreignHits = 0;
  let trClueHits = 0;

  for (const raw of rawTokens) {
    const isCapitalized = raw[0] >= "A" && raw[0] <= "Z";
    const low = raw.toLowerCase();

    if (UNAMBIGUOUS_FOREIGN_WORDS.has(low)) {
      foreignHits += 1;
      continue;
    }

    // Lowercase w/q/x never appear in native Turkish common words
    if (!isCapitalized && /[wqx]/.test(low)) {
      foreignHits += 1;
      continue;
    }

    if (!hasTrChars && low.length >= 5 && ENGLISH_ORTHO_RE.test(low)) {
      foreignHits += 1;
      continue;
    }

    if (TURKISH_DIACRITIC_RE.test(raw) || (low.length >= 5 && TURKISH_ASCII_CLUE_RE.test(low))) {
      trClueHits += 1;
    }
  }

  const total = rawTokens.length;

  // Even if a sentence mentions one Turkish proper noun (e.g. "Mehmet Öz works at the hospital"),
  // a high density of unambiguous foreign function words marks the sentence as foreign.
  if (total >= 4 && foreignHits >= 2 && foreignHits / total >= 0.34 && trClueHits <= 1) {
    return "non-tr";
  }

  if (hasTrChars) {
    return "tr";
  }

  if (foreignHits >= 2 || (total <= 3 && foreignHits >= 1 && trClueHits === 0)) {
    return "non-tr";
  }

  if (trClueHits >= 1 && foreignHits === 0) {
    return "tr";
  }

  return "uncertain";
}

/**
 * Worker-side sentence gate combining orthographic signals with Zemberek's own
 * diacritic-restoring (`deasciify`) + informal (`analyzeDisambiguate`) morphological solver.
 * Preserves 100% of Turkish, ASCII-Turkish ("Bugun kitablar cok guzel"), and informal/typo
 * Turkish while suppressing English/German/French/Spanish sentences.
 */
export function isSentenceLikelyTurkish(
  sentence: string,
  client: ZemberekWasmClient
): boolean {
  const quick = classifyTextOrthography(sentence);
  if (quick === "tr") return true;
  if (quick === "non-tr") return false;

  const alphaTokens = sentence.match(ALPHA_TOKEN_RE);
  if (!alphaTokens || alphaTokens.length === 0) return true;

  // Inspect raw + deasciified morphological analyses
  const rawAnalyses = client.analyzeDisambiguate(sentence).filter((t) => /\p{L}/u.test(t.surface));
  if (rawAnalyses.length === 0) return true;

  let knownCount = rawAnalyses.filter((t) => t.known).length;

  if (knownCount * 2 < rawAnalyses.length) {
    const deasc = client.deasciify(sentence, "disambiguation");
    if (deasc !== sentence) {
      const deascAnalyses = client
        .analyzeDisambiguate(deasc)
        .filter((t) => /\p{L}/u.test(t.surface));
      const deascKnown = deascAnalyses.filter((t) => t.known).length;
      if (deascKnown > knownCount) {
        knownCount = deascKnown;
      }
    }
  }

  const total = rawAnalyses.length;
  if (knownCount > 0 && knownCount / total >= 0.34) {
    return true;
  }

  // Single-word or 2-word ASCII typo fallback: check if normalization produces a known Turkish word
  // via 1-edit Turkish morphophonemic repair (e.g. "herkez" -> "herkes", "kitablar" -> "kitaplar")
  if (total <= 2) {
    const norm = client.normalize(sentence);
    if (norm && norm !== sentence) {
      const normAnalyses = client
        .analyzeDisambiguate(norm)
        .filter((t) => /\p{L}/u.test(t.surface));
      if (normAnalyses.length > 0 && normAnalyses.every((t) => t.known)) {
        // Guard against short 2-3 letter English words accidentally matching a Turkish word
        const minLen = Math.min(...alphaTokens.map((w) => w.length));
        if (minLen >= 5 || TURKISH_ASCII_CLUE_RE.test(sentence.toLowerCase())) {
          return true;
        }
      }
    }
    return false;
  }

  return false;
}
