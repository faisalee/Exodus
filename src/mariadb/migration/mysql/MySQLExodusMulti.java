package mariadb.migration.mysql;

import mariadb.migration.DBConHandler;
import mariadb.migration.MariaDBConnect;
import mariadb.migration.ExodusWorker;
import mariadb.migration.Logger;
import mariadb.migration.TableHandler;
import mariadb.migration.Util;

public class MySQLExodusMulti implements Runnable {
	private DBConHandler SourceCon, TargetCon;
	private TableHandler Table;
	private Thread MySQLWorkerThread;
	private String ThreadName;
	long RowsMigrated=0;
	String MigrationTask;

	public MySQLExodusMulti(TableHandler iTable) {
		String SchemaName, TableName;
		Table = iTable;
		
		SourceCon = new MySQLConnect(Util.getPropertyValue("SourceDB"));
		TargetCon = new MariaDBConnect(Util.getPropertyValue("TargetDB"));

		SchemaName = Table.getSchemaName();
		TableName = Table.getTableName();
		ThreadName = SchemaName + "." + TableName;
		
		//Dry Run or normal Migration
		if (Util.getPropertyValue("DryRun").equals("NO") && Util.getPropertyValue("MigrateData").equals("YES")) {
			MigrationTask = "MIGRATE";
		} else {
			MigrationTask = "SKIP";
		}
	}

	public void run() {
		try {
			//iF Table has not already migrated or the OverWrite flag is set to YES then proceed
			if (!Table.hasTableMigrated() || Util.getPropertyValue("OverWriteTables").equals("YES")) {
					ExodusWorker MySQLExodusWorker = new ExodusWorker(SourceCon, TargetCon, Table, MigrationTask);
				RowsMigrated = MySQLExodusWorker.Exodus();
			} else {
				System.out.println("Processing Table " + Util.rPad(Table.getFullTableName(), 63, " ") + "--> " + "Already Migrated, SKIPPED!");
			}
		} catch (Exception e) {
			System.out.println("Exception While running Main Thread!");
			new Logger(Util.getPropertyValue("LogPath") + "/Exodus.err", "Exception While running Main Thread - " + e.getMessage(), true);
			e.printStackTrace();
		} finally {
			SourceCon.DisconnectDB();
			TargetCon.DisconnectDB();
		}
	}
    
	//Trigger the Thread for a given Table!
	public void start() {
    	if (MySQLWorkerThread == null) {
    		MySQLWorkerThread = new Thread (this, ThreadName);
    	}
    	MySQLWorkerThread.start();
	}

  public boolean isThreadActive() { 
		return MySQLWorkerThread.isAlive(); 
	}	
}
