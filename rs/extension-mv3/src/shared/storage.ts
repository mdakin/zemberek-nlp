import { DEFAULT_SETTINGS, type ExtensionSettings } from "./protocol";

const SETTINGS_KEY = "zmbk_settings";
const SYNC_CHUNK_PREFIX = "zmbk_dict_chunk_";
const SYNC_META_KEY = "zmbk_dict_meta";
const LOCAL_OVERFLOW_KEY = "zmbk_dict_overflow";

// Stay safely inside chrome.storage.sync QUOTA_BYTES_PER_ITEM (8,192 B) and 100 KB total
const MAX_SYNC_CHUNKS = 10;
const MAX_BYTES_PER_CHUNK = 7_000;

const utf8Encoder = new TextEncoder();

export async function loadSettings(): Promise<ExtensionSettings> {
  const raw = await chrome.storage.sync.get(SETTINGS_KEY);
  const stored = raw[SETTINGS_KEY] as Partial<ExtensionSettings> | undefined;
  return {
    enabled: stored?.enabled ?? DEFAULT_SETTINGS.enabled,
    engineProfile: stored?.engineProfile ?? DEFAULT_SETTINGS.engineProfile,
    circumflexMode: stored?.circumflexMode ?? DEFAULT_SETTINGS.circumflexMode,
    ignoreNonTurkish: stored?.ignoreNonTurkish ?? DEFAULT_SETTINGS.ignoreNonTurkish,
    suppressNativeSpellcheck:
      stored?.suppressNativeSpellcheck ?? DEFAULT_SETTINGS.suppressNativeSpellcheck,
  };
}

export async function saveSettings(partial: Partial<ExtensionSettings>): Promise<ExtensionSettings> {
  const current = await loadSettings();
  const updated: ExtensionSettings = { ...current, ...partial };
  await chrome.storage.sync.set({ [SETTINGS_KEY]: updated });
  return updated;
}

export async function loadUserDictionary(): Promise<string[]> {
  const metaObj = await chrome.storage.sync.get(SYNC_META_KEY);
  const chunkCount = Number(metaObj[SYNC_META_KEY]?.chunkCount ?? 0);
  const hasOverflow = Boolean(metaObj[SYNC_META_KEY]?.hasOverflow);

  const words: string[] = [];
  if (chunkCount > 0) {
    const keys = Array.from(
      { length: Math.min(chunkCount, MAX_SYNC_CHUNKS) },
      (_, i) => `${SYNC_CHUNK_PREFIX}${i}`
    );
    const chunkData = await chrome.storage.sync.get(keys);
    for (const key of keys) {
      const arr = chunkData[key];
      if (Array.isArray(arr)) {
        for (const w of arr) {
          if (typeof w === "string" && w.trim()) {
            words.push(w.trim());
          }
        }
      }
    }
  }

  if (hasOverflow) {
    const localObj = await chrome.storage.local.get(LOCAL_OVERFLOW_KEY);
    const overflow = localObj[LOCAL_OVERFLOW_KEY];
    if (Array.isArray(overflow)) {
      for (const w of overflow) {
        if (typeof w === "string" && w.trim()) {
          words.push(w.trim());
        }
      }
    }
  }

  return Array.from(new Set(words));
}

export async function saveUserDictionary(words: readonly string[]): Promise<string[]> {
  const deduped = Array.from(
    new Set(words.map((w) => w.trim()).filter((w) => w.length > 0))
  );

  const chunks: string[][] = [];
  const overflow: string[] = [];
  let currentChunk: string[] = [];
  let currentBytes = 2; // "[]"

  for (const word of deduped) {
    const wordJsonBytes = utf8Encoder.encode(JSON.stringify(word)).byteLength + 1;
    if (currentBytes + wordJsonBytes > MAX_BYTES_PER_CHUNK && currentChunk.length > 0) {
      if (chunks.length < MAX_SYNC_CHUNKS) {
        chunks.push(currentChunk);
        currentChunk = [];
        currentBytes = 2;
      }
    }

    if (chunks.length >= MAX_SYNC_CHUNKS) {
      overflow.push(word);
    } else {
      currentChunk.push(word);
      currentBytes += wordJsonBytes;
      if (currentBytes > MAX_BYTES_PER_CHUNK) {
        chunks.push(currentChunk);
        currentChunk = [];
        currentBytes = 2;
      }
    }
  }
  if (currentChunk.length > 0) {
    if (chunks.length < MAX_SYNC_CHUNKS) {
      chunks.push(currentChunk);
    } else {
      overflow.push(...currentChunk);
    }
  }

  const prevMeta = await chrome.storage.sync.get(SYNC_META_KEY);
  const prevCount = Number(prevMeta[SYNC_META_KEY]?.chunkCount ?? 0);

  const syncPayload: Record<string, unknown> = {
    [SYNC_META_KEY]: {
      chunkCount: chunks.length,
      hasOverflow: overflow.length > 0,
    },
  };
  for (let i = 0; i < chunks.length; i++) {
    syncPayload[`${SYNC_CHUNK_PREFIX}${i}`] = chunks[i];
  }
  await chrome.storage.sync.set(syncPayload);

  if (prevCount > chunks.length) {
    const staleKeys: string[] = [];
    for (let i = chunks.length; i < prevCount; i++) {
      staleKeys.push(`${SYNC_CHUNK_PREFIX}${i}`);
    }
    await chrome.storage.sync.remove(staleKeys);
  }

  if (overflow.length > 0) {
    await chrome.storage.local.set({ [LOCAL_OVERFLOW_KEY]: overflow });
  } else {
    await chrome.storage.local.remove(LOCAL_OVERFLOW_KEY);
  }

  return deduped;
}

export async function addWordToDictionary(word: string): Promise<string[]> {
  const trimmed = word.trim();
  if (!trimmed) return loadUserDictionary();
  const existing = await loadUserDictionary();
  if (!existing.includes(trimmed)) {
    existing.push(trimmed);
    return saveUserDictionary(existing);
  }
  return existing;
}

export async function removeWordFromDictionary(word: string): Promise<string[]> {
  const trimmed = word.trim();
  const existing = await loadUserDictionary();
  return saveUserDictionary(existing.filter((w) => w !== trimmed));
}
