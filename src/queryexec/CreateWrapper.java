package queryexec;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.Charset;

import java.util.ArrayList;
import java.util.List;

import FileUtils.WriteOutputFile;
import bPlusTree.BPlusTreeBuilder;
import bPlusTree.SecondaryBPlusTree;
import iterators.FileReaderIterator;
import iterators.TableScanIterator;
import net.sf.jsqlparser.expression.PrimitiveValue;
import net.sf.jsqlparser.parser.CCJSqlParser;
import net.sf.jsqlparser.parser.ParseException;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.Index;
import objects.ColumnDefs;
import objects.IndexDef;
import objects.SchemaStructure;
import utils.Config;
import utils.Constants;
import utils.Utils;

public class CreateWrapper {

	public void createHandler(String tableName) {
		String path = Config.createFileDir + tableName;
		try {
			String queryStr = (String) WriteOutputFile.readObjectInFile(path);
			InputStream inputStream = new ByteArrayInputStream(queryStr.getBytes(Charset.forName("UTF-8")));
			CCJSqlParser parser = new CCJSqlParser(inputStream);
			if(queryStr != null) {
				Statement query = parser.Statement();
				this.createHandler(query, queryStr);
			}
		} catch (ClassNotFoundException | IOException | ParseException e) {
			e.printStackTrace();
		}
	}
	
	public void createHandler(Statement query, String querystr) {
		CreateTable createtab = (CreateTable) query;
		Table tbal = createtab.getTable();
		String path = Config.createFileDir + tbal.getName();
		List<Index> indexes = createtab.getIndexes();
		List<ColumnDefinition> cdef = createtab.getColumnDefinitions();

		if(!Utils.isFileExists(path)) {
			try {
				for(Index index: indexes) {
					if(index.getType().equals(Constants.PRIMARY_KEY)) {
						for(String primaryKey: index.getColumnsNames()) {
							FileReaderIterator iter = new FileReaderIterator(tbal);
							BPlusTreeBuilder btree = new BPlusTreeBuilder(iter, tbal, cdef, primaryKey);
							btree.build();
							btree.writeMapToFile();
							SchemaStructure.bTreeMap.put(tbal.getName(), btree);
							break;
						}
					} else {
						// Non-primary index -> build a secondary (non-clustered)
						// index per column. No sortedness assumption; posting lists.
						for(String secondaryKey: index.getColumnsNames()) {
							FileReaderIterator iter = new FileReaderIterator(tbal);
							SecondaryBPlusTree sidx = new SecondaryBPlusTree(iter, tbal, cdef, secondaryKey);
							sidx.build();
							SchemaStructure.secondaryIndexMap
									.computeIfAbsent(tbal.getName(), k -> new java.util.HashMap<>())
									.put(secondaryKey, sidx);
						}
					}
				}
				WriteOutputFile.writeObjectInFile(path, querystr);
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		} 
//		else {
//			for(Index index: indexes) {
//				if(index.getType().equals(Constants.PRIMARY_KEY)) {
//					for(String primaryKey: index.getColumnsNames()) {
//						//BPlusTreeBuilder btree = new BPlusTreeBuilder(tbal, cdef, primaryKey);
//						//btree.readMapFromFile();
//						//SchemaStructure.bTreeMap.put(tbal.getName(), btree);
//						break;
//					}
//				}
//			}			
//		}
		List<ColumnDefs> cdfList = new ArrayList<ColumnDefs>();
		for (ColumnDefinition cd : cdef) {
			ColumnDefs c = new ColumnDefs();
			c.cdef = cd;
			cdfList.add(c);
			SchemaStructure.columnTableMap.put(cd.getColumnName(), tbal);		
		}
		SchemaStructure.schema.put(tbal.getName(), cdfList);
		SchemaStructure.tableMap.put(tbal.getName(), tbal);
		SchemaStructure.indexMap.put(tbal.getName(), indexes);
	}
}
