# Secondary Index — Implementation Plan

## Goal

Add secondary (non-clustered) index support to the SQL engine. Primary indexing already exists and assumes the CSV is physically sorted on the key. Secondary indexing must work on **any column**, with **no assumption of sort order**, so matching rows are scattered throughout the file.

The whole design reduces to one shift: from **one offset per key** (primary) to a **posting list of offsets per key** (secondary), and from **seek-once-read-forward** to **seek-once-per-matching-row**.

---

## Design decisions to lock first

**1. What the posting list stores.**
Store raw byte offsets. It gives a single-hop lookup (offset → file position) and matches how the primary index already works on immutable CSVs. Trade-off: offsets break if the file is ever rewritten. Storing primary keys instead would survive reorganization but adds a second lookup through the primary tree. For this engine, raw offsets are the right call — note the trade-off but don't build the two-level path yet.

**2. One tree class, two behaviors.**
Reuse the existing `BPlusTree` / `LeafNode`. Add an `isSecondary` flag on `BPlusTree` that switches leaf-node behavior between scalar (primary) and posting-list (secondary). This keeps the primary path untouched and avoids a parallel class hierarchy.

**3. Which predicates use the index.**
Route only equality and `IN` predicates through the secondary index. Range predicates and joins fall back to a full scan for now (sequential reads beat scattered random I/O for low-selectivity ranges). Selectivity-based costing can come later.

**4. Storage isolation.**
Keep secondary trees in a **separate map** from the primary `bTreeMap`, so the two index kinds never collide and the primary path is unaffected.

---

## Phase 1 — Storage structure

**Touches:** `BPlusTree`, `BPlusTree.LeafNode`

- Add `isSecondary` flag to `BPlusTree` (set at construction).
- Change the leaf value slot so it can hold a list of offsets per key instead of a single offset.
- `insertValue()` gains a branch: when `isSecondary` is true and the key already exists in the leaf, **append** the offset to that key's posting list; otherwise create a new key with a single-element list. When `isSecondary` is false, behavior is unchanged.
- Node split logic must carry posting lists with their keys — no change to split thresholds, just make sure the values move alongside the keys.
- Lookup returns the **whole posting list** for a key (primary returns a scalar; secondary returns the list).

**Done when:** inserting scattered duplicate keys produces one key entry with a multi-offset posting list, and lookup returns every offset.

---

## Phase 2 — Build path

**Touches:** `BPlusTreeBuilder`

- Add `buildSecondary()` alongside the existing `build()`. Leave `build()` completely untouched.
- `buildSecondary()` scans the file **once**, top to bottom, and for **every row**:
  - extract the indexed column value,
  - compute that row's byte offset (cumulative length + newline, same accounting `build()` already uses),
  - insert `(value → rowOffset)`.
- Critically, there is **no** `startPoint`/`startOffset` guard. Removing that guard is exactly what drops the sortedness assumption.

**Done when:** building on an unsorted column yields correct posting lists for every distinct value.

---

## Phase 3 — Query execution

**Touches:** new `SecondarySeekIterator`, `BPlusTreeBuilder` search entry point

- Add a search entry point that probes the secondary tree with a key and returns the posting list.
- Create `SecondarySeekIterator` (parallel to `TableSeekIterator`) that:
  - holds the posting list,
  - on each `next()`, seeks to the next offset via the existing `RandomAccessFile`, reads exactly one row, parses it using the same type-switch `TableSeekIterator` already uses,
  - `hasNext()` is just "more offsets remain" — **no equality re-check**, because the posting list already guarantees every entry matches.

**Contrast to primary:** primary seeks once and streams forward until the key changes; secondary seeks once per matching row.

**Done when:** a lookup on an indexed column returns exactly the matching rows, in posting-list order.

---

## Phase 4 — Registration

**Touches:** `SchemaStructure`, `CreateWrapper`

- In `SchemaStructure`, add `secondaryIndexMap`: table name → (column name → builder), separate from `bTreeMap`.
- In `CreateWrapper.createHandler()`, branch on index type:
  - `PRIMARY KEY` → existing clustered build → `bTreeMap` (unchanged).
  - `INDEX` / `KEY` → `buildSecondary()` → `secondaryIndexMap`.
- Support multiple secondary indexes per table (each is independent and scans the file once at build time).

**Done when:** a `CREATE TABLE` with both a primary key and one or more secondary indexes registers all trees in the correct maps.

---

## Phase 5 — Planner routing

**Touches:** `Optimzer`, `IndexJoinIterator`

- In the per-table predicate extraction, check whether the predicate column has an entry in `secondaryIndexMap`.
- If it does **and** the predicate is equality or `IN`, route the scan through the secondary seek path instead of `TableScanIterator`.
- Otherwise, fall back to a full scan (ranges, non-indexed columns, no predicate).
- Extend `IndexJoinIterator` so an index nested-loop join can fire when the inner relation's join column is indexed in `secondaryIndexMap`, not just when it's the primary key.

**Done when:** an equality filter on an indexed column visibly uses the seek path, and a join on a secondary-indexed column chooses the index join.

---

## Testing checklist

- Unsorted column with duplicates → correct posting lists.
- Column with all-distinct values → each posting list has length 1.
- Equality lookup returns exactly the matching rows.
- `IN (a, b, c)` returns the union across posting lists.
- Multiple secondary indexes on one table coexist without interference.
- Primary index behavior is byte-for-byte unchanged (regression guard).
- Empty result (key not present) returns no rows, not an error.
- Index join on a secondary column matches the result of a plain nested-loop join.

---

## Risks and notes

- **Space:** secondary indexes are dense (one entry per row, not per distinct key), so they cost more disk than the sparse primary index. Acceptable; they're optional and independent.
- **Random I/O:** N matching rows = N seeks. Fine for selective queries; the planner's equality/`IN`-only rule keeps it away from low-selectivity ranges where a scan wins.
- **Offset fragility:** raw offsets assume the CSV is immutable after build. If mutation is ever added, secondary indexes must be rebuilt (or switched to primary-key posting lists).
- **Don't touch the primary path.** Every change is additive: a flag, a new build method, a new iterator, a new map, a new planner branch. The existing clustered index must keep passing its tests throughout.

---

## Sequence summary

1. Storage: posting-list leaf + `isSecondary` flag.
2. Build: `buildSecondary()`, no sort assumption.
3. Execute: `SecondarySeekIterator`, one seek per row.
4. Register: `secondaryIndexMap` + `CreateWrapper` branch.
5. Plan: route equality/`IN` predicates and index joins to the secondary path.
