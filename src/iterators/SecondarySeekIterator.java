package iterators;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.sf.jsqlparser.expression.DateValue;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.PrimitiveValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.schema.Table;
import objects.ColumnDefs;
import objects.SchemaStructure;

/**
 * Iterates the rows referenced by a secondary index posting list.
 *
 * Contrast with {@link TableSeekIterator}: the primary (clustered) seek does ONE
 * seek and then streams forward until the key changes, because matching rows are
 * contiguous. A secondary index's matching rows are scattered, so this iterator
 * performs one {@link RandomAccessFile#seek(long)} per offset and reads exactly
 * one row each time. No equality re-check is needed: every offset in the posting
 * list is already a confirmed match.
 */
public class SecondarySeekIterator implements DefaultIterator {

	private final List<String> columns;
	private final Table table;
	private final List<Integer> offsets;
	private int cursor = 0;

	private RandomAccessFile raf;
	private final List<ColumnDefs> cdefs;
	private final Map<String, Integer> columnMap;

	public SecondarySeekIterator(String path, Table table, List<Integer> offsets, List<String> queryColumns) {
		this.columns = queryColumns;
		this.table = table;
		this.offsets = offsets;
		this.cdefs = SchemaStructure.schema.get(table.getName());
		this.columnMap = createColumnMapper(this.cdefs);
		try {
			this.raf = new RandomAccessFile(path, "r");
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public boolean hasNext() {
		return this.offsets != null && this.cursor < this.offsets.size();
	}

	@Override
	public List<PrimitiveValue> next() {
		if (!hasNext()) {
			return null;
		}
		int offset = this.offsets.get(this.cursor);
		this.cursor++;
		String tuple = null;
		try {
			this.raf.seek(offset);
			tuple = this.raf.readLine();
		} catch (IOException e) {
			e.printStackTrace();
		}
		if (tuple == null) {
			return null;
		}
		return parse(tuple);
	}

	private List<PrimitiveValue> parse(String tuple) {
		List<PrimitiveValue> map = new ArrayList<PrimitiveValue>();
		String[] row = tuple.split("\\|");
		for (String elem : this.columns) {
			if (this.columnMap.containsKey(elem)) {
				int index = this.columnMap.get(elem);
				ColumnDefs cdef = this.cdefs.get(index);
				String value = row[index];
				PrimitiveValue pm;
				switch (cdef.cdef.getColDataType().getDataType().toLowerCase()) {
					case "int":
						pm = new LongValue(value);
						break;
					case "string":
					case "varchar":
					case "char":
						pm = new StringValue(value);
						break;
					case "decimal":
						pm = new DoubleValue(value);
						break;
					case "date":
						pm = new DateValue(value);
						break;
					default:
						pm = new StringValue(value);
						break;
				}
				map.add(pm);
			}
		}
		return map;
	}

	private Map<String, Integer> createColumnMapper(List<ColumnDefs> cdefs) {
		Map<String, Integer> mapper = new HashMap<String, Integer>();
		int index = 0;
		for (ColumnDefs cdef : cdefs) {
			mapper.put(this.table.getName() + "." + cdef.cdef.getColumnName(), index);
			index += 1;
		}
		return mapper;
	}

	@Override
	public void reset() {
		this.cursor = 0;
	}

	@Override
	public List<String> getColumns() {
		return this.columns;
	}
}
