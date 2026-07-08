package dubstep;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.StringReader;
import interfaces.UnionWrapper;
import net.sf.jsqlparser.parser.CCJSqlParser;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.Union;
import queryexec.CreateWrapper;
import queryexec.SelectWrapper;
import utils.Config;
import utils.Utils;
import net.sf.jsqlparser.statement.create.table.CreateTable;

class Main {

	public static void main(String args[]) throws Exception {
		Config.isInMemory = true;
		Utils.createDirectory(Config.folderName);
		Utils.createDirectory(Config.createFileDir);		
		Utils.createDirectory(Config.bPlusTreeDir);
		
		File createDir = new File(Config.createFileDir);
		CreateWrapper createWrapper = new CreateWrapper();
		for(File file: createDir.listFiles()) {
			createWrapper.createHandler(file.getName());
		}
		
		for (String arg : args) {
			if (arg.equals("--in-mem")) {
				Config.isInMemory = true;
			}
		}
		System.out.println("$> "); // print a prompt
		// Read stdin ONCE. Recreating the BufferedInputStream every iteration drops
		// input: the stream reads ahead in blocks, so the first one buffers the
		// following statements and the next fresh stream then blocks at EOF.
		BufferedInputStream buf = new BufferedInputStream(System.in);
		CreateWrapper cw = new CreateWrapper();
		while (true) {

			/* accumulate bytes up to the ';' terminator (59); stop at EOF (-1) */
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			int result = buf.read();
			while (result != 59 && result != -1) {
				baos.write((byte) result);
				result = buf.read();
			}

			String querystr = baos.toString().trim();
			if (!querystr.isEmpty()) {
				try {
					CCJSqlParser parser = new CCJSqlParser(new StringReader(querystr));
					Statement query = parser.Statement();
					if (query instanceof Select) {
						Select select = (Select) query;
						SelectBody selectbody = select.getSelectBody();
						if (selectbody instanceof PlainSelect) {
							PlainSelect plainSelect = (PlainSelect) selectbody;
							new SelectWrapper(plainSelect).parse();
						} else {
							Union union = (Union) selectbody;
							new UnionWrapper(union).parse();
						}
					} else if (query instanceof CreateTable) {
						cw.createHandler(query, querystr);
					}
				} catch (Exception e) {
					// Report and skip a malformed statement rather than aborting the REPL.
					System.err.println("Skipping statement: " + e.getMessage());
				}
				System.out.println("$>"); // prompt after executing each command
			}

			if (result == -1) {
				break; // end of input
			}
		}
	}
}
