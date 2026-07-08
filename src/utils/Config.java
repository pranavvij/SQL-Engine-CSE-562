package utils;
public class Config {
	public static Boolean isInMemory;	
	public static int blockSize = 5000;
	public static int BRANCHING_FACTOR = 7000000;
	public static String bucket_location = "bucket/";	
	public static String fileName = "tempfolder/level_";
	public static String folderName = "tempfolder/";
	public static String createFileDir = "createDB/";
	public static String bPlusTreeDir = "bPlusTreeDir/";
	// Relative to the working directory you launch from. The repo ships a small
	// sample dataset in data/ so the project runs out of the box; point this at a
	// larger TPC-H directory to use your own data.
	public static String databasePath = "data/";
	
	
//	public static String databasePath = "C:\\Users\\ayush\\Documents\\Sanity_Check_Examples\\data\\Database\\";	
//	this.csvFile = "C:\\Users\\ayush\\Documents\\Sanity_Check_Examples\\data\\" + tableName.toLowerCase() + ".csv";			
//	this.csvFile = "C:\\Users\\Amit\\Desktop\\Sanity_Check_Examples\\data\\50Data\\" + tableName.toLowerCase() + ".csv";
//	this.csvFile = "C:\\Users\\ayush\\Documents\\Sanity_Check_Examples\\data\\" + tableName.toLowerCase() + ".dat";				
//	this.csvFile = "/Users/pranavvij/Desktop/data/checkpoint2/" + tableName.toLowerCase() + ".csv";
//	public static String databasePath = "C:\\\\Users\\\\Amit\\\\Desktop\\\\Sanity_Check_Examples\\\\data\\\\Data\\\\";
	
	
//	public static String databasePath = "/Users/pranavvij/Desktop/Database Systems/data/checkpoint3_big/";
//	public static String databasePath = "C:\\\\Users\\\\Amit\\\\Desktop\\\\Sanity_Check_Examples\\\\data\\\\checkpoint3_big\\\\";

//	public static String databasePath = "/Users/pranavvij/Desktop/Database Systems/data/checkpoint3_big/";
//	public static String databasePath = "C:\\\\Users\\\\Amit\\\\Desktop\\\\Sanity_Check_Examples\\\\data\\\\database\\\\";
//public static String databasePath =  = "data/" + tableName + ".csv";		

}	
