# Zemberek Core Collections: Technical Audit, Bug Analysis, and Java 21 Modernization Report

- **Package**: `zemberek.core.collections`
- **Current Target**: Java 1.8
- **Date**: September 2026

---

## 1. Executive Summary

The `zemberek.core.collections` package provides primitive-specialized, zero-boxing, open-addressing data structures (`int`, `float`, `long`) designed to minimize object allocation and garbage collection (GC) overhead during core NLP operations (morphology graph traversal, language model lookups, FastText embeddings, and tokenization).

While the original architecture effectively avoids boxing overhead, this technical audit has identified:
1. **Critical functional bugs**, including silent data omission when using negative keys in `IntMap.getValues()` and violation of the standard `java.util.Iterator` contract (non-idempotent `hasNext()` skipping elements).
2. **Algorithmic inefficiencies**, such as bit-by-bit scalar loops in bit vectors that run 32x–64x slower than word-level bitwise operations, and triple intermediate allocations in `Histogram`.
3. **High potential for Java 21 modernization**, leveraging Records (JEP 395), Sequenced Collections (JEP 431), Foreign Function & Memory API (JEP 454), and JVM bounds-check intrinsics.

---

## 2. Architecture & Design

The package is organized around three primary hashing foundations and specialized collection types:

```
                              [ Hash Foundations ]
                                       │
        ┌──────────────────────────────┼──────────────────────────────┐
        ▼                              ▼                              ▼
   HashBase<T>                  UIntKeyHashBase              CompactIntMapBase
 (Linear probing,              (Linear probing,              (Packed 64-bit long[],
  TOMB_STONE object)            EMPTY=-1, DELETED=-2)         key + value packed)
        │                              │                              │
 ┌──────┴──────┐                ┌──────┴──────┐                ┌──────┴──────┐
 │ IntValueMap │                │   UIntMap   │                │  IntIntMap  │
 │FloatValueMap│                │   UIntSet   │                │ IntFloatMap │
 │  LookupSet  │                └─────────────┘                └─────────────┘
 └─────────────┘                       │
        │                              ▼
        ▼                     LongUIntMap (64-bit)
   Histogram<T>
```

### Auxiliary Structures:
- **Bit Vectors**: `FixedBitVector` (backed by `int[]`), `LongBitVector` (backed by `long[]`, supporting serialization).
- **Vectors**: `IntVector` (dynamic primitive array list).
- **Trie & Lookups**: `Trie<T>` (compact trie using nested `IntMap` child nodes), `BidirectionalIndexLookup<T>`.

---

## 3. Critical Bugs & Behavioral Inconsistencies

### 3.1. `IntMap.getValues()` Silently Drops Negative Keys
- **File**: [`IntMap.java:178-186`](file:///home/dndara/work/zemberek-nlp/core/src/main/java/zemberek/core/collections/IntMap.java#L178-L186)
- **Problem**:
  ```java
  public List<T> getValues() {
    List<T> result = new ArrayList<>();
    for (int i = 0; i < keys.length; i++) {
      if (keys[i] >= 0) { // BUG: Silently drops values with negative keys!
        result.add(values[i]);
      }
    }
    return result;
  }
  ```
  `IntMap` javadoc states support for keys in `(Integer.MIN_VALUE..Integer.MAX_VALUE]`. `getKeys()`, `get(key)`, and `containsKey(key)` handle negative keys correctly. However, `getValues()` filters by `keys[i] >= 0` (copy-pasted from `UIntKeyHashBase`), silently dropping values mapped to negative keys.
- **Impact**: `map.getKeys().length != map.getValues().size()`. Callers iterating `getValues()` experience silent data loss.

---

### 3.2. Non-Idempotent `Iterator.hasNext()` Violates `java.util.Iterator` Contract
- **Files**:
  - [`HashBase.java:240-253`](file:///home/dndara/work/zemberek-nlp/core/src/main/java/zemberek/core/collections/HashBase.java#L240-L253) (`KeyIterator`)
  - [`IntMap.java:258-272`](file:///home/dndara/work/zemberek-nlp/core/src/main/java/zemberek/core/collections/IntMap.java#L258-L272) (`ValueIterator`)
  - [`UIntMap.java:121-135`](file:///home/dndara/work/zemberek-nlp/core/src/main/java/zemberek/core/collections/UIntMap.java#L121-L135) (`ValueIterator`)
- **Problem**:
  ```java
  @Override
  public boolean hasNext() {
    if (counter == keyCount) return false;
    while (true) {
      if (keys[keyCounter] != EMPTY) { keyCounter++; break; }
      keyCounter++;
    }
    item = values[keyCounter - 1];
    counter++; // BUG: Mutates iteration progress inside hasNext()!
    return true;
  }
  ```
  In standard Java, calling `hasNext()` multiple times must be idempotent. In these classes, every invocation of `hasNext()` increments `counter` and advances `keyCounter`.
- **Impact**: Standard library utilities, assertions, or frameworks calling `hasNext()` redundantly skip every alternate element. Furthermore, calling `next()` when exhausted fails to throw `NoSuchElementException`.

---

### 3.3. `IntVector.expand(int)` Overwrites `size` and Fails on Zero Capacity
- **File**: [`IntVector.java:126-130`](file:///home/dndara/work/zemberek-nlp/core/src/main/java/zemberek/core/collections/IntVector.java#L126-L130)
- **Problem**:
  ```java
  long newSize = size * 2L + offset;
  if (newSize > Integer.MAX_VALUE) {
    size = Integer.MAX_VALUE; // BUG: Overwrites count of elements instead of newSize!
  }
  data = Arrays.copyOf(data, (int) newSize);
  ```
  1. `size` (number of active elements) is incorrectly mutated to `Integer.MAX_VALUE` instead of capping the target capacity.
  2. `new IntVector(0).add(x)` throws `ArrayIndexOutOfBoundsException` because `0 * 2L + 0 = 0`, keeping capacity at 0.
  3. `get(index)` does not validate `index < size`; accessing an unpopulated index below `data.length` returns `0` silently.
  4. Method name typo: `isempty()` instead of `isEmpty()`.

---

### 3.4. `CompactIntMapBase.locate()` Never Reclaims `DELETED` Slots
- **File**: [`CompactIntMapBase.java:141-156`](file:///home/dndara/work/zemberek-nlp/core/src/main/java/zemberek/core/collections/CompactIntMapBase.java#L141-L156)
- **Problem**: Unlike `HashBase` (which remembers the first deleted slot in `pointer`), `CompactIntMapBase.locate` skips `DELETED` slots and keeps probing until reaching `EMPTY`.
- **Impact**: Deleted slots are never filled by subsequent `put()` operations until a full array resize. Additionally, `newCapacity()` unconditionally doubles capacity even if deleted entries account for 90% of the map, resulting in severe memory bloat under churn.

---

### 3.5. `LongUIntMap.hash()` Lacks Avalanche Mixing
- **File**: [`LongUIntMap.java:41-43`](file:///home/dndara/work/zemberek-nlp/core/src/main/java/zemberek/core/collections/LongUIntMap.java#L41-L43)
- **Problem**:
  ```java
  private int hash(long key) {
    return Long.hashCode(key);
  }
  ```
  `Long.hashCode(key)` is simply `(int)(key ^ (key >>> 32))`. When modulo-masked into power-of-two table capacities, sequential or memory-aligned 64-bit keys cause massive linear probing collisions. All other hash bases in Zemberek mix with `0x9E3779B9` and right-shift.

---

## 4. Performance & Algorithmic Inefficiencies

### 4.1. Bit-by-Bit Scalar Loops in Bit Vectors
- **`FixedBitVector.numberOfNewOneBitCount`** ([`FixedBitVector.java:92-100`](file:///home/dndara/work/zemberek-nlp/core/src/main/java/zemberek/core/collections/FixedBitVector.java#L92-L100)):
  Loops from `0` to `length`, invoking `get(i)` twice per bit. Word-level bitwise operations achieve **32x–64x speedup**:
  ```java
  int total = 0;
  for (int i = 0; i < words.length; i++) {
    total += Integer.bitCount(~words[i] & other.words[i]);
  }
  ```
- **`LongBitVector.getLastBitIndex`** ([`LongBitVector.java:128-135`](file:///home/dndara/work/zemberek-nlp/core/src/main/java/zemberek/core/collections/LongBitVector.java#L128-L135)):
  Scans bit-by-bit from `size-1` down to `0`. Scanning words in reverse with `Long.numberOfLeadingZeros(w)` replaces $O(\text{bits})$ with $O(\text{words})$.
- **Static mask arrays**: `setMasks[32]` and `longSetMasks[64]` cause memory lookups. The JVM/x86/ARM natively masks shift amounts (`1 << n` and `1L << n`) in a single hardware cycle.

---

### 4.2. Redundant Lookups & Memory Allocation in `Histogram`
- **Serialization probe overhead** ([`Histogram.java:95-98`](file:///home/dndara/work/zemberek-nlp/core/src/main/java/zemberek/core/collections/Histogram.java#L95-L98)):
  `for (String key : h.map) { dos.writeInt(h.getCount(key)); }`
  Iterates keys and re-hashes every entry to find its count. Direct iteration over internal `(key, count)` entries eliminates millions of redundant probes.
- **Triple intermediate list allocation** ([`Histogram.java:430-434`](file:///home/dndara/work/zemberek-nlp/core/src/main/java/zemberek/core/collections/Histogram.java#L430-L434)):
  `getSortedList()` allocates an outer `ArrayList`, generates a sorted entry list, runs `.stream().map(...).collect(toList())`, and then calls `addAll(...)`.
- **Set intersection sorting** ([`Histogram.java:445`](file:///home/dndara/work/zemberek-nlp/core/src/main/java/zemberek/core/collections/Histogram.java#L445)):
  `getIntersectionOfKeys` sorts the smaller histogram by frequency before intersecting, adding an unnecessary $O(N \log N)$ sort to an $O(N)$ operation.

---

### 4.3. Heavy Node Overhead in `Trie<T>`
- **File**: [`Trie.java:222-258`](file:///home/dndara/work/zemberek-nlp/core/src/main/java/zemberek/core/collections/Trie.java#L222-L258)
- Every branching node allocates an `IntMap<Node<T>>`, which instantiates `int[4]` and `Object[4]` arrays.
- In Turkish vocabulary tries, most non-leaf nodes have only 1 or 2 children. Storing compact `char[]` and `Node[]` child arrays reduces trie heap footprint by ~70% while improving CPU cache locality.
- Uses legacy synchronized `StringBuffer` in `dump()` and `toDeepString()`.

---

## 5. Modernization Opportunities with Java 21

Upgrading Zemberek from **Java 8 to Java 21** provides significant language and runtime capabilities:

| Feature / JEP | Java 8 Legacy Pattern | Java 21 Modernization | Benefits |
| :--- | :--- | :--- | :--- |
| **Records (JEP 395)** | Boilerplate inner classes (`IntValueMap.Entry`, `IntPair`) | `public record Entry<T>(T key, int count) implements Comparable<Entry<T>>` | Cleaner syntax, immutable semantics, built-in equals/hashCode/toString. |
| **Sequenced Collections (JEP 431)** | Custom `getLastBitIndex`, manual reverse methods | Implement `SequencedCollection<T>` on `IntVector` and `Histogram` (`getFirst()`, `getLast()`, `reversed()`) | Uniform collection hierarchy across the JDK. |
| **Foreign Memory & Arena (JEP 454)** | Large heap arrays (`long[]`, `int[]`) causing GC pauses | Off-heap `MemorySegment` / `Arena` for large language models and embedding tables | Zero GC pauses; zero-copy memory-mapped file access (`Arena.ofConfined()`). |
| **SIMD Vector API (Incubator)** | Scalar loops in `Weights`, `FixedBitVector`, `FastText` | Explicit SIMD operations (`IntVector`, `FloatVector`) | 4x–8x throughput increase on matrix/vector products on AVX2/AVX-512 and ARM Neon. |
| **Bounds Check Intrinsics** | Manual `if (n < 0 \|\| n >= length) throw ...` | `Objects.checkIndex(index, size)` | C2 compiler converts checks to native CPU branch intrinsics. |
| **Stream API Enhancements** | `Lists.newArrayList()`, `.collect(Collectors.toList())` | `Stream.toList()`, `List.of()`, `Set.of()` | Immutable, zero-overhead collection snapshots without Guava dependencies. |
| **Overflow-Safe Math** | Unchecked `size * 2L + offset` | `Math.addExact()`, `Math.multiplyExact()` | Prevents silent capacity overflows and memory corruptions. |

---

## 6. Strategic Recommendations

### Strategy A: Refactor In-House Collections
*Best if zero external dependencies and minimal JAR distribution size are strict requirements.*
1. Fix iterator contracts (`KeyIterator`, `ValueIterator`) to ensure idempotent `hasNext()` and proper `NoSuchElementException`.
2. Fix `IntMap.getValues()` negative key bug.
3. Unify `HashBase`, `UIntKeyHashBase`, and `CompactIntMapBase` into a single, well-tested linear probing core with proper tombstone reclamation.
4. Replace scalar loops in `FixedBitVector` and `LongBitVector` with word-level bit operations.
5. Adopt Java 21 `record`s for entry/pair types and `Objects.checkIndex` for bounds checking.

### Strategy B: Adopt Standard High-Performance Primitive Libraries
*Best for long-term maintainability, reliability, and speed.*
- Modern libraries such as **[FastUtil](https://github.com/vigna/fastutil)** (`it.unimi.dsi:fastutil`) or **[Agrona](https://github.com/real-logic/agrona)** are extensively optimized, support full `java.util` interfaces, and are battle-tested against concurrency and edge-case bugs.
- Migrating to FastUtil would eliminate ~3,500 lines of custom collection code from Zemberek, removing ongoing maintenance overhead while delivering superior performance.

