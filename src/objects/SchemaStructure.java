package objects;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import bPlusTree.BPlusTreeBuilder;
import bPlusTree.SecondaryBPlusTree;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.create.table.Index;

public class SchemaStructure {
	public static HashMap<String, List<ColumnDefs>> schema = new HashMap<>();
	public static HashMap<String, Table> tableMap = new HashMap<>();
	public static HashMap<String, Table> columnTableMap = new HashMap<>();
	public static List<Expression> whrexpressions = new ArrayList<Expression>();
	public static HashMap<String, List<Index>> indexMap = new HashMap<>();
	public static HashMap<String, BPlusTreeBuilder> bTreeMap = new HashMap<>();
	// table name -> (column name -> secondary index) ; kept separate from bTreeMap
	public static HashMap<String, HashMap<String, SecondaryBPlusTree>> secondaryIndexMap = new HashMap<>();
	public static HashMap<String, Integer> precedenceMap = new HashMap<>();
}