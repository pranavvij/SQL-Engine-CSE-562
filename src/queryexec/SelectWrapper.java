package queryexec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import iterators.DefaultIterator;
import iterators.GroupByIterator;
import iterators.IndexJoinIterator;
import iterators.HashJoinIterator;
import iterators.JoinIterator;
import iterators.LimitIterator;
import iterators.OrderByIterator;
import iterators.ProjectionIterator;
import iterators.ResultIterator;
import iterators.SelectionIterator;
import iterators.SortMergeIterator;
import iterators.TableScanIterator;

import bPlusTree.SecondaryBPlusTree;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.PrimitiveValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.create.table.Index;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectExpressionItem;
import net.sf.jsqlparser.statement.select.SelectItem;
import objects.ColumnDefs;
import objects.SchemaStructure;
import utils.Config;
import utils.Optimzer;
import utils.Utils;

public class SelectWrapper {
	private PlainSelect plainselect;
	private List<SelectItem> selectItems;
	private Expression whereExp;
	private List<Column> groupBy;
	private List<Join> joins;
	private List<OrderByElement> orderBy;
    private HashMap<String, List<Index>> indexMap;
	private Limit limit;
	private Expression having;
	private Map<String, List<String>> queryColumns;

	public SelectWrapper(PlainSelect plainselect) {
		this.plainselect = plainselect;
	}

	public void parse() throws Exception {
		DefaultIterator iter = null;
		FromItem fromItem = this.plainselect.getFromItem();
		this.selectItems = this.plainselect.getSelectItems();
		this.whereExp = this.plainselect.getWhere();
		this.groupBy = this.plainselect.getGroupByColumnReferences();
		this.orderBy = this.plainselect.getOrderByElements();
		this.limit = this.plainselect.getLimit();
		this.having = this.plainselect.getHaving();
//		SchemaStructure.whrexpressions = Utils.splitAndClauses(whereExp);
		SchemaStructure.whrexpressions = Utils.splitWhereClauses(whereExp);
		indexMap = SchemaStructure.indexMap;
		this.queryColumns = extractQueryCols(this.selectItems,this.whereExp,this.joins,this.groupBy,this.orderBy);
		
		if (fromItem instanceof Table) {
			Table table = (Table) fromItem;
			iter = new TableScanIterator(table , this.queryColumns.get(table.getName()));
			iter = pushDownSelectPredicate(table, iter);
		}

		DefaultIterator result = iter;

		while (result.hasNext()) {

			if ((this.joins = this.plainselect.getJoins()) != null) {
				for (Join join : joins) {
					FromItem item = join.getRightItem();
					if (item instanceof Table) 
					{
						Table rightTb = (Table) item;
						DefaultIterator iter2 = new TableScanIterator(rightTb , this.queryColumns.get(rightTb.getName()));
						iter2 = pushDownSelectPredicate(rightTb, iter2);
						result = pushDownJoinPredicate(result, iter2, join);
					}
				}
			}

			if (this.whereExp != null) 
			{
				List<Expression> tempExp = SchemaStructure.whrexpressions;
				
				
				if (tempExp != null && tempExp.size() > 0) 
				{
					Expression exp = Utils.conquerExpression(tempExp);
					result = new SelectionIterator(result, exp);
				}
			}

			if (this.groupBy != null) {
				for (Column key : groupBy) {
					String xKey = key.getColumnName();
					if (key.getTable() == null) {
						key.setTable(SchemaStructure.columnTableMap.getOrDefault(xKey, (Table) fromItem));
					}
				}
				if (Config.isInMemory) {
					result = new GroupByIterator(result, this.groupBy, (Table) fromItem, this.selectItems);
				} 
			}

			if (this.selectItems != null) {
				result = new ProjectionIterator(result, this.selectItems, (Table) fromItem, this.groupBy);
			}

			if (this.orderBy != null) {
				for (OrderByElement key : this.orderBy) {
					String xKey = key.getExpression().toString();
					if (xKey.split("\\.").length == 1) {
						Table defTable = (Table) fromItem;
						Column cCol = new Column();
						if (isContainingColumn(xKey, SchemaStructure.schema.get(defTable.getName()))) {
							cCol.setColumnName(xKey);
							cCol.setTable(defTable);
						} else {
							// case for alias
							cCol.setColumnName(xKey);
							cCol.setTable(SchemaStructure.columnTableMap.getOrDefault(xKey, null));
						}
						key.setExpression(cCol);
					}
				}
				if (Config.isInMemory) {
					result = new OrderByIterator(this.orderBy, result);
				} 
			}

			if (this.limit != null) {
				result = new LimitIterator(result, this.limit);
			}

			ResultIterator res = new ResultIterator(result);
			
			while (res.hasNext()) 
			{
				res.next();
			}
		}
	}

	private DefaultIterator pushDownSelectPredicate2(Table table, DefaultIterator iter) {
		// TODO Auto-generated method stub
		List<Expression> tempExp = Optimzer.getExpressionForSelectionPredicate(table,SchemaStructure.schema.get(table.getName()), SchemaStructure.whrexpressions);
		
		List<Index> lst = SchemaStructure.indexMap.get(table.toString());
		List<Index> secondaryIndex = new ArrayList<Index>();
		List<Index> nonSecondaryIndex = new ArrayList<Index>();
		for(Index l : lst )
		{
			if(l.getType().equals("INDEX"))
				secondaryIndex.add(l);
			else
				nonSecondaryIndex.add(l);
		}
		List<Expression> inQSecondaryIndex = new ArrayList<Expression>();
		List<Expression> inQnonSecondaryIndex = new ArrayList<Expression>();
		for(Expression exp : tempExp)
		{
			if(secondaryIndex.contains(exp.toString().split("\\.")[1]))
			{
				inQSecondaryIndex.add(exp);
			}
			else
			{
				inQnonSecondaryIndex.add(exp);
			}
		}
		
		if (inQSecondaryIndex != null && inQSecondaryIndex.size() > 0) 
		{	
//			Expression exp  = null;
//			if(inQnonSecondaryIndex.size() > 1)
//				exp = Utils.conquerExpression(inQSecondaryIndex); // what if only one?
//			else
//				exp = inQSecondaryIndex.get(0);
			for( Expression exp : inQSecondaryIndex )
			{
				//iter = new IndexScanIterator(iter, exp);
			}

		}
		if (inQnonSecondaryIndex != null && inQnonSecondaryIndex.size() > 0) 
		{
			Expression exp = Utils.conquerExpression(inQnonSecondaryIndex);
			iter = new SelectionIterator(iter, exp);
		}
		
//		if (tempExp != null && tempExp.size() > 0) {
//			Expression exp = Utils.conquerExpression(tempExp);
//			iter = new SelectionIterator(iter, exp);
//		}
		return iter;
	}

//	private void function(DefaultIterator iterator , HashMap<String, List<Index>> indexMap, String columnName, Table tableName) {
//		// TODO Auto-generated method stub
//		List<Index> indexes =  indexMap.get( tableName.toString());
//		
//		if( indexes.contains(columnName))
//		{
//			iterator = new IndexSelectionIterator(iterator , tableName , columnName);
//		}
//		else
//		{
//			iterator = new IndexSelectionIterator(iterator , tableName , columnName);
//		}
//	}
	
	private Map<String, List<String>> extractQueryCols(List<SelectItem> selectItems2, Expression whereExp2, List<Join> joins2,
			List<Column> groupBy2, List<OrderByElement> orderBy2) {
		Map<String, List<String>> queryColumns = new HashMap<>();
		Set<String> collist = new HashSet();
		// TODO Auto-generated method stub
		if(joins2!=null) {
			for(Join j : joins2) {
				Expression e = j.getOnExpression();
				collist.addAll(Utils.splitExpCols(e));
			}
		}

		if(selectItems2!=null) {
			for (SelectItem sel : selectItems2) {
				
				
				SelectExpressionItem selex = (SelectExpressionItem) sel;
				if(selex.getExpression() instanceof Column) {
					collist.add(sel.toString());
				}else if(selex.getExpression() instanceof Function){
					if(selex.getAlias()!=null) {
						List<String> temp = new ArrayList<>();

						if(queryColumns.containsKey("ALIAS")) {
							queryColumns.get("ALIAS").add(selex.getAlias());
						}else {
							temp.add(selex.getAlias());
							queryColumns.put("ALIAS", temp);
						}
					}
					Function e = (Function) selex.getExpression();
					if(e.getParameters()!=null) {
						List<Expression> elist = e.getParameters().getExpressions();
						for (Expression temp : elist) {
							collist.addAll(Utils.splitExpCols2(temp));
						}
					}else {
						String n = e.getName()+"(*)."+selex.getAlias();
						if(queryColumns.containsKey("ALIAS")) {
							queryColumns.get("ALIAS").add(n);	
						}else {
							queryColumns.put("ALIAS", new ArrayList<>(Arrays.asList(n)));
						}
					}
				}
				
			}
		}
		if(whereExp2!=null) {
			Set<Expression> wherelist = Utils.splitAllClauses(whereExp2);
			for(Expression exp : wherelist) {
				collist.addAll(Utils.splitExpCols(exp));
			}
		}
		if(groupBy2!=null) {
			for(Column col : groupBy2) {
				collist.add(col.getWholeColumnName());	
			}
		}
//		if(orderBy2!=null) {
//			for(OrderByElement ord: orderBy2) {
//				Expression e = ord.getExpression();
//				collist.addAll(Utils.splitExpCols(e));
//			}
//		}
		List<String> list = new ArrayList<>();
		for(String x : collist) {
			String[] splitcol = x.split("\\.");
			if(queryColumns.containsKey(splitcol[0])) {
				queryColumns.get(splitcol[0]).add(splitcol[0]+"."+splitcol[1]);
			}else{
				List<String> temp = new ArrayList<String>();
				temp.add(splitcol[0]+"."+splitcol[1]);
				queryColumns.put(splitcol[0], temp);
			}
		}
		collist = null;
		return queryColumns;
	}

	private boolean isContainingColumn(String xKey, List<ColumnDefs> list) {
		for (ColumnDefs cdef : list) {
			if (cdef.cdef.getColumnName().equals(xKey)) {
				return true;
			}
		}
		return false;
	}

	public List<Expression> splitAndClauses(Expression e) {
		List<Expression> ret = new ArrayList<>();
		if (e instanceof AndExpression) {
			AndExpression a = (AndExpression) e;
			ret.addAll(splitAndClauses(a.getLeftExpression()));
			ret.addAll(splitAndClauses(a.getRightExpression()));
		} else {
			ret.add(e);
		}
		return null;
	}

	public DefaultIterator pushDownSelectPredicate(Table table, DefaultIterator iter) {
		List<Expression> tempExp = Optimzer.getExpressionForSelectionPredicate(table,
				SchemaStructure.schema.get(table.getName()), SchemaStructure.whrexpressions);

		if (tempExp == null || tempExp.size() == 0) {
			return iter;
		}

		// Route one equality predicate on a secondary-indexed column through the
		// seek path, replacing the full table scan `iter`. Gated on isInMemory to
		// match the index-join path and keep disk-mode output unchanged.
		List<Expression> remaining = new ArrayList<Expression>(tempExp);
		HashMap<String, SecondaryBPlusTree> secMap = SchemaStructure.secondaryIndexMap.get(table.getName());
		if (Config.isInMemory && secMap != null) {
			Expression chosen = null;
			for (Expression exp : tempExp) {
				if (!(exp instanceof EqualsTo)) {
					continue;
				}
				EqualsTo eq = (EqualsTo) exp;
				Column col = null;
				PrimitiveValue literal = null;
				if (eq.getLeftExpression() instanceof Column && eq.getRightExpression() instanceof PrimitiveValue) {
					col = (Column) eq.getLeftExpression();
					literal = (PrimitiveValue) eq.getRightExpression();
				} else if (eq.getRightExpression() instanceof Column && eq.getLeftExpression() instanceof PrimitiveValue) {
					col = (Column) eq.getRightExpression();
					literal = (PrimitiveValue) eq.getLeftExpression();
				}
				if (col == null) {
					continue;
				}
				SecondaryBPlusTree sidx = secMap.get(col.getColumnName());
				if (sidx == null) {
					continue;
				}
				// posting list already guarantees the match: no re-check needed
				iter = sidx.search(literal, this.queryColumns.get(table.getName()));
				chosen = exp;
				break;
			}
			if (chosen != null) {
				remaining.remove(chosen);
			}
		}

		if (remaining.size() > 0) {
			Expression exp = Utils.conquerExpression(remaining);
			iter = new SelectionIterator(iter, exp);
		}
		return iter;
	}

	public DefaultIterator pushDownJoinPredicate(DefaultIterator leftIterator, DefaultIterator rightIterator, Join joinDefault) {

		List<String> leftColumns = leftIterator.getColumns();
		List<String> rightColumns = rightIterator.getColumns();
		
		DefaultIterator result = null;
		Expression exp = Optimzer.getExpressionForJoinPredicate(leftColumns, rightColumns,
				SchemaStructure.whrexpressions);
		if (exp != null) {
			Join join = new Join();
			join.setOnExpression(exp);
			EqualsTo equalTo = (EqualsTo) exp;
			
			if (Config.isInMemory) {	
				//Column HoldingLeftColumn = isTableHoldingIndexedWhichIndex(leftIterator, equalTo);
				//Column HoldingRightColumn = isTableHoldingIndexedWhichIndex(rightIterator, equalTo);
				Column HoldingRightColumn  = null;
				Column HoldingLeftColumn  = null;
				
						
				if(HoldingLeftColumn != null && HoldingRightColumn != null) {
					if(Utils.isHoldingPrecedence(((TableScanIterator)leftIterator).tab, ((TableScanIterator)rightIterator).tab)) {
						result = new IndexJoinIterator(rightIterator, leftIterator, join, 
								HoldingRightColumn, HoldingLeftColumn, this.queryColumns);
					} else{
						result = new IndexJoinIterator(leftIterator, rightIterator, join, 
								HoldingLeftColumn, HoldingRightColumn, this.queryColumns);	
					}
				} else if(HoldingLeftColumn != null) {
					if(HoldingLeftColumn.toString().equals(equalTo.getLeftExpression().toString())) {
						result = new IndexJoinIterator(rightIterator, leftIterator, join, 
							(Column)equalTo.getRightExpression(), (Column)equalTo.getLeftExpression(), this.queryColumns);
					} else {
						result = new IndexJoinIterator(rightIterator, leftIterator, join, 
								(Column)equalTo.getLeftExpression(), (Column)equalTo.getRightExpression(), this.queryColumns);
					}
				} else if(HoldingRightColumn != null) {
					if(HoldingRightColumn.toString().equals(equalTo.getLeftExpression().toString())) {
						result = new IndexJoinIterator(leftIterator, rightIterator, join, 
							(Column)equalTo.getRightExpression(), (Column)equalTo.getLeftExpression(), this.queryColumns);
					} else {
						result = new IndexJoinIterator(leftIterator, rightIterator, join, 
								(Column)equalTo.getLeftExpression(), (Column)equalTo.getRightExpression(), this.queryColumns);
					}
				} else {
					//result = new JoinIterator(leftIterator, rightIterator, join);
					result = new HashJoinIterator(leftIterator, rightIterator, join);
				}
			} else {
				try {
					result = new SortMergeIterator(leftIterator, rightIterator, join, this.selectItems);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		} else {
			if(joinDefault.getOnExpression() instanceof EqualsTo) {
				EqualsTo equalTo = (EqualsTo) joinDefault.getOnExpression();
				Join join = joinDefault;
				
				if (Config.isInMemory) {	
					Column HoldingLeftColumn = isTableHoldingIndexedWhichIndex(leftIterator, equalTo);
					Column HoldingRightColumn = isTableHoldingIndexedWhichIndex(rightIterator, equalTo);
						
					if(HoldingLeftColumn != null && HoldingRightColumn != null) {
						if(Utils.isHoldingPrecedence(((TableScanIterator)leftIterator).tab, ((TableScanIterator)rightIterator).tab)) {
							result = new IndexJoinIterator(rightIterator, leftIterator, join, 
									HoldingRightColumn, HoldingLeftColumn, this.queryColumns);
						} else{
							result = new IndexJoinIterator(leftIterator, rightIterator, join, 
									HoldingLeftColumn, HoldingRightColumn,this.queryColumns);	
						}
					} else if(HoldingLeftColumn != null) {
						if(HoldingLeftColumn.toString().equals(equalTo.getLeftExpression().toString())) {
							result = new IndexJoinIterator(rightIterator, leftIterator, join, 
								(Column)equalTo.getRightExpression(), (Column)equalTo.getLeftExpression(),this.queryColumns);
						} else {
							result = new IndexJoinIterator(leftIterator, rightIterator, join, 
									(Column)equalTo.getLeftExpression(), (Column)equalTo.getRightExpression(),this.queryColumns);
						}
					} else if(HoldingRightColumn != null) {
						if(HoldingRightColumn.toString().equals(equalTo.getLeftExpression().toString())) {
							result = new IndexJoinIterator(rightIterator, leftIterator, join, 
								(Column)equalTo.getRightExpression(), (Column)equalTo.getLeftExpression(),this.queryColumns);
						} else {
							result = new IndexJoinIterator(leftIterator, rightIterator, join, 
									(Column)equalTo.getLeftExpression(), (Column)equalTo.getRightExpression(),this.queryColumns);
						}
					} else {
						result = new HashJoinIterator(leftIterator, rightIterator, join);
					}
				} else {
					try {
						result = new SortMergeIterator(leftIterator, rightIterator, join, this.selectItems);
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			} else {
				result = new JoinIterator(leftIterator, rightIterator, joinDefault);
			}
		}
		return result;
	}
	
	private Column isTableHoldingIndexedWhichIndex(DefaultIterator iterator, EqualsTo equalTo) {
		TableScanIterator tableIterator = null;
		if(iterator instanceof TableScanIterator ) {
			tableIterator = (TableScanIterator) iterator;
			String[] left = equalTo.getLeftExpression().toString().split("\\.");
			String[] right = equalTo.getRightExpression().toString().split("\\.");
			
			if(Utils.isPrimaryKey(left[1], SchemaStructure.indexMap.get(tableIterator.tab.getName()))) {
				if(left[0].equals(tableIterator.tab.getName())) {
					return (Column)equalTo.getLeftExpression();
				} else {
					return (Column)equalTo.getRightExpression();
				}
			}
			if(Utils.isPrimaryKey(right[1], SchemaStructure.indexMap.get(tableIterator.tab.getName()))) {
				if(right[0].equals(tableIterator.tab.getName())) {
					return (Column)equalTo.getLeftExpression();
				} else {
					return (Column)equalTo.getRightExpression();
				}
			}

			// Same selection for a secondary (non-clustered) index on this table:
			// the join column being indexed lets the index nested-loop join fire.
			HashMap<String, SecondaryBPlusTree> secMap =
					SchemaStructure.secondaryIndexMap.get(tableIterator.tab.getName());
			if(secMap != null) {
				if(secMap.containsKey(left[1])) {
					if(left[0].equals(tableIterator.tab.getName())) {
						return (Column)equalTo.getLeftExpression();
					} else {
						return (Column)equalTo.getRightExpression();
					}
				}
				if(secMap.containsKey(right[1])) {
					if(right[0].equals(tableIterator.tab.getName())) {
						return (Column)equalTo.getLeftExpression();
					} else {
						return (Column)equalTo.getRightExpression();
					}
				}
			}
		}
		return null;
	}
}










