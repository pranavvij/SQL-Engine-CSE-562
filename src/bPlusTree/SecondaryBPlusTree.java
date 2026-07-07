package bPlusTree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import iterators.DefaultIterator;
import iterators.RAIterator;
import iterators.SecondarySeekIterator;
import net.sf.jsqlparser.expression.DateValue;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.PrimitiveValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import utils.Config;

/**
 * A secondary (non-clustered) index.
 *
 * Unlike the primary index in {@link BPlusTreeBuilder}, this makes NO assumption
 * that the CSV is physically sorted on the indexed column. Rows sharing an index
 * value are scattered throughout the file, so each key maps to a *posting list*
 * of byte offsets (one per matching row) rather than a single offset.
 *
 * Implementation reuses the existing {@link BPlusTree} unchanged: the tree maps
 * each distinct key -> an index into {@link #postings}, where each posting entry
 * is the full list of row offsets for that key. Because every distinct key is
 * inserted exactly once (de-duplicated during build), the tree's scalar
 * key->int contract is preserved and the primary index code path is untouched.
 */
public class SecondaryBPlusTree {

	private final RAIterator iterator;
	private final Table table;
	private final List<ColumnDefinition> cdefs;
	private final String indexStr;

	private BPlusTree bPlusTree;
	/** postings.get(i) = all row byte-offsets for the key whose tree value is i. */
	private final List<List<Integer>> postings = new ArrayList<List<Integer>>();
	/** raw-string value -> posting index, used to de-dup keys while building. */
	private final Map<String, Integer> keyToPosting = new HashMap<String, Integer>();

	private int columnType = -1; // resolved data-type marker for building keys

	public SecondaryBPlusTree(RAIterator iterator, Table table, List<ColumnDefinition> cdefs, String indexStr) {
		this.iterator = iterator;
		this.table = table;
		this.cdefs = cdefs;
		this.indexStr = indexStr;
	}

	/**
	 * Scans the whole file once. For every row (no sortedness assumed) records
	 * (indexColumnValue -> thisRowByteOffset) into the posting lists.
	 */
	public SecondaryBPlusTree build() {
		this.bPlusTree = new BPlusTree(Config.BRANCHING_FACTOR, indexStr);
		int position = getPositionOfColumn(this.indexStr);
		if (position < 0) {
			return this; // column not found; empty index
		}
		int offset = 0; // byte offset of the start of the current row
		while (this.iterator.hasNext()) {
			String next = this.iterator.next();
			if (next == null) {
				break;
			}
			String[] arr = next.split("\\|");
			String rawValue = arr[position];

			Integer postingIndex = this.keyToPosting.get(rawValue);
			if (postingIndex == null) {
				// first time we see this value: new posting list + new tree entry
				postingIndex = this.postings.size();
				this.postings.add(new ArrayList<Integer>());
				this.keyToPosting.put(rawValue, postingIndex);
				this.bPlusTree.insert(buildKey(rawValue), postingIndex);
			}
			this.postings.get(postingIndex).add(offset);

			offset += (next.length() + 1); // +1 for the newline (matches primary build)
		}
		return this;
	}

	/** All row offsets for a key, or an empty list if the key is absent. */
	public List<Integer> getPostings(PrimitiveValue searchValue) {
		if (this.bPlusTree == null) {
			return Collections.emptyList();
		}
		int idx = this.bPlusTree.search(searchValue);
		if (idx < 0 || idx >= this.postings.size()) {
			return Collections.emptyList();
		}
		return this.postings.get(idx);
	}

	/** Equality lookup convenience keyed directly on the raw CSV field value. */
	public List<Integer> getPostingsByRaw(String rawValue) {
		Integer idx = this.keyToPosting.get(rawValue);
		if (idx == null) {
			return Collections.emptyList();
		}
		return this.postings.get(idx);
	}

	/**
	 * Returns an iterator over exactly the rows matching {@code searchValue},
	 * seeking once per matching row (no read-forward, since rows are scattered).
	 */
	public DefaultIterator search(PrimitiveValue searchValue, List<String> queryColumns) {
		List<Integer> offsets = getPostings(searchValue);
		String path = Config.databasePath + this.table.getName() + ".csv";
		return new SecondarySeekIterator(path, this.table, offsets, queryColumns);
	}

	public String getIndexColumn() {
		return this.indexStr;
	}

	public int distinctKeys() {
		return this.postings.size();
	}

	private PrimitiveValue buildKey(String value) {
		if (this.columnType == -1) {
			this.columnType = resolveType();
		}
		switch (this.columnType) {
			case 1:  return new LongValue(value);   // int
			case 2:  return new DoubleValue(value); // decimal
			case 3:  return new DateValue(value);   // date
			default: return new StringValue(value); // string/varchar/char
		}
	}

	private int resolveType() {
		int position = getPositionOfColumn(this.indexStr);
		if (position < 0) {
			return 0;
		}
		String type = this.cdefs.get(position).getColDataType().getDataType().toLowerCase();
		switch (type) {
			case "int":     return 1;
			case "decimal": return 2;
			case "date":    return 3;
			default:        return 0;
		}
	}

	private int getPositionOfColumn(String indexStr) {
		int index = 0;
		for (ColumnDefinition cdef : this.cdefs) {
			if (cdef.getColumnName().toLowerCase().equals(indexStr.toLowerCase())) {
				return index;
			}
			index++;
		}
		return -1;
	}
}
