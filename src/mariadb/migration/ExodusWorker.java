package mariadb.migration;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
public class ExodusWorker {
	private boolean DeltaProcessing=false, MultiThreaded=false, RetryOnErrors=false;
	private String MigrationTask, LogPath;
	private long BATCH_SIZE = 0, BATCH_CALC_SIZE = 0;
	private DBConHandler SourceCon, TargetCon;
	private TableHandler Table;
	private ExodusProgress Prog;
    private Logger TableLog;

	public ExodusWorker(DBConHandler iSourceCon, DBConHandler iTargetCon, TableHandler iTable, String iMigrationTask) {
		Table = iTable;
		MigrationTask = iMigrationTask;
		Prog = new ExodusProgress(Table);

        SourceCon = iSourceCon;
        TargetCon = iTargetCon; 

		LogPath = Util.getPropertyValue("LogPath");
		TableLog = new Logger(LogPath + "/" + Table.getFullTableName() + ".log", true);
		BATCH_SIZE = Integer.valueOf(Util.getPropertyValue("TransactionSize"));
		BATCH_CALC_SIZE = Integer.valueOf(Util.getPropertyValue("BatchTimeCalculatorSize"));
		RetryOnErrors = Util.getPropertyValue("RetryOnErrors").equals("YES");

		//Truncate the table if Overwrite option is defined
		if (Util.getPropertyValue("OverWriteTables").equals("YES")) {
			Util.ExecuteScript(TargetCon, "TRUNCATE TABLE " + Table.getFullTableName());
		} else {
			//Identify if the Table has already been partially Migrated or not!
			if (ExodusProgress.hasTablePartiallyMigrated(Table.getSchemaName(), Table.getTableName())) {
				//Truncate the table if Overwrite option is defined
				if (Util.getPropertyValue("OverwritePartiallyMigratedTables").equals("YES")) {
					System.out.println("Overwriting the Partially Migrated Table " + Table.getTableName());
					Util.ExecuteScript(TargetCon, "TRUNCATE TABLE " + Table.getFullTableName());
				} else {
					DeltaProcessing = true;
				}
			}
		}

		//This decides how the output will be logged on Screen
		MultiThreaded = (Integer.valueOf(Util.getPropertyValue("ThreadCount")) > 1);
	}

	//Data Migration Logic goes here... Takes care of Delta/Full migration
	public long Exodus() throws SQLException {
		String SourceSelectSQL, TargetInsertSQL, ColumnType, ErrorString, OutString;
		ResultSetMetaData SourceResultSetMeta;
	    PreparedStatement PreparedStmt;
		long IntValue, MigratedRows=0, TotalRecords=0, CommitCount=0, SecondsTaken, RecordsPerSecond=0, SecondsRemaining=0;
		float TimeforOneRecord;
		boolean BatchError = false;

		//Don't need it
		//IsLastBatchCycle = false, 

		//To Track Start/End and Estimate Time of Completion
		LocalTime StartDT;
		//LocalTime EndDT;
		int ColumnCount;
		long BatchRowCounter, BatchCounter=0, NumberOfBatches=0, TmpBatchSize=0, RerunBatchRowCounter=0;
		float ProgressPercent;

		byte[] BlobObj;
		double DoubleValue;
		BigDecimal BigDecimalValue;
		float FloatValue;

		if (MigrationTask.equals("SKIP")) {
			System.out.println("\nTable " + Table.getFullTableName() + " Skipped");
			TableLog.WriteLog("Table " + Table.getFullTableName() + " Skipped");
			Prog.ProgressEnd();
			return 0;
		}

		ResultSet SourceResultSetObj;
		Statement SourceStatementObj;

		//Remote Tables Not needed hence Disabling this block
		////Create the Remote Table for Delta Processing
		//for (String DeltaTabScript : Table.getDeltaTableScript()) {
		//	Util.ExecuteScript(SourceCon, DeltaTabScript);
		//}

		//Check for Delta conditions and actions
		TotalRecords = Table.getRecordCount();

		if (DeltaProcessing) {
			SourceSelectSQL = Table.getDeltaSelectScript();
			TableLog.WriteLog("Delta Processing Started for - " + Table.getTableName() + " Previously Migrated " + Table.getDeltaRecordCount() + "/" + TotalRecords);
		} else {
			SourceSelectSQL = Table.getTableSelectScript();
			TableLog.WriteLog("Processing Started for - " + Table.getTableName() + " Total Records to Migrate " + Util.numberFormat.format(TotalRecords));

			//Try to create the target table and skip this entire process if any errors
			if (Util.ExecuteScript(TargetCon, Table.getTableScript()) < 0) {
				TableLog.WriteLog("Failed to create target table, Process aborted!");
				return -1;
			}

			new Logger(Util.getPropertyValue("DDLPath") + "/" + Table.getFullTableName() + ".sql", Table.getTableScript() + ";", false, false);
		}

		//See if first records will be able to fit a single BATCH, IF Total Record Count is ZERO, SET BATCH SIZE to 1
		//THIS IS TO AVOID DIVIDE BY ZERO errors
	    if (BATCH_SIZE > TotalRecords) {
			BATCH_SIZE = (TotalRecords == 0 ? 1 : TotalRecords);
		}

		//This will be used handle the last batch for a resultset
		NumberOfBatches = (TotalRecords / BATCH_SIZE);

		TargetInsertSQL = Table.getTargetInsertScript();

		//Don't print this if it's multi-threaded! 
		if (!MultiThreaded) {
			//First output for tables that take longer to open the resultset
			System.out.print("Fetching Resultset for " + Table.getFullTableName() + "...");
		}

		try {
			//Reverse Scrollable Resultset only when RetryOnErrors!
			SourceStatementObj = SourceCon.getDBConnection().createStatement((RetryOnErrors ? ResultSet.TYPE_SCROLL_INSENSITIVE : ResultSet.TYPE_FORWARD_ONLY), 
																		ResultSet.CONCUR_READ_ONLY);
			
			//Tuning of the Batch FetchSize for better network utilization
			SourceStatementObj.setFetchSize((int)BATCH_SIZE);

			SourceResultSetObj = SourceStatementObj.executeQuery(SourceSelectSQL);

			//Get the Meta data of the Source ResultSet, this will be used to get the list of columns in the ResultSet.
			SourceResultSetMeta = SourceResultSetObj.getMetaData();
	        ColumnCount = SourceResultSetMeta.getColumnCount();
	        PreparedStmt = TargetCon.getDBConnection().prepareStatement(TargetInsertSQL);
	        //Parse through the source query result-set!

	        ErrorString="";
	        BatchRowCounter=0;
			PreparedStmt.clearBatch();

			StartDT = LocalTime.now();
			//EndDT = LocalTime.now();
			RecordsPerSecond = BATCH_SIZE;

			//Default Time Remaining is 30 Minute  
			SecondsRemaining=1;

			//Process the Batch
			while (SourceResultSetObj.next()) {
				try {
			        for (int Col = 1; Col <= ColumnCount; Col++) {
			            ColumnType = SourceResultSetMeta.getColumnTypeName(Col);

			            switch (ColumnType) {
		                	case "INTEGER":
			                case "INTEGER UNSIGNED": 
			                	IntValue = SourceResultSetObj.getLong(Col);
			                    if(SourceResultSetObj.wasNull())
			                    	PreparedStmt.setNull(Col, java.sql.Types.INTEGER);
			                    else
			                    	PreparedStmt.setLong(Col, IntValue);
			                    break;
							case "TINYCLOB":
							case "CLOB":
							case "MEDIUMCLOB":
							case "LONGCLOB":
							case "CHAR": 
							case "VARCHAR":
								PreparedStmt.setString(Col, SourceResultSetObj.getString(Col));
			                    break;
							case "DATETIME": 
							case "TIMESTAMP": PreparedStmt.setTimestamp(Col, SourceResultSetObj.getTimestamp(Col)); 
		                    	break;
			                case "DATE": PreparedStmt.setDate(Col, SourceResultSetObj.getDate(Col));
			                    break;
			                case "TIME": PreparedStmt.setTime(Col, SourceResultSetObj.getTime(Col));
			                    break;
			                case "BOOL": 
							case "BOOLEAN": 
							case "BIT":
			                case "TINYINT": 
			                case "TINYINT UNSIGNED": 
			                    IntValue = SourceResultSetObj.getLong(Col);
			                    if(SourceResultSetObj.wasNull())
			                    	PreparedStmt.setNull(Col, java.sql.Types.TINYINT);
			                    else
			                    	PreparedStmt.setLong(Col, IntValue);
			                    break;
			                case "SMALLINT": 
			                case "SMALLINT UNSIGNED": 
			                    IntValue = SourceResultSetObj.getLong(Col);
			                    if(SourceResultSetObj.wasNull())
			                    	PreparedStmt.setNull(Col, java.sql.Types.SMALLINT);
			                    else
			                    	PreparedStmt.setLong(Col, IntValue);
			                    break;
			                case "BIGINT": 
			                case "BIGINT UNSIGNED": 
								BigDecimalValue = SourceResultSetObj.getBigDecimal(Col);
								
			                    if(SourceResultSetObj.wasNull())
			                    	PreparedStmt.setNull(Col, java.sql.Types.BIGINT);
			                    else
									PreparedStmt.setBigDecimal(Col, BigDecimalValue);
			                    break;                                
			                case "DOUBLE":
			                	DoubleValue = SourceResultSetObj.getDouble(Col);
			                    if(SourceResultSetObj.wasNull())
			                    	PreparedStmt.setNull(Col, java.sql.Types.DOUBLE);
			                    else
			                    	PreparedStmt.setDouble(Col, DoubleValue);
			                    break;
			                case "DECIMAL": 
			                    BigDecimalValue = SourceResultSetObj.getBigDecimal(Col);
			                    if(SourceResultSetObj.wasNull())
			                    	PreparedStmt.setNull(Col, java.sql.Types.DECIMAL);
			                    else
			                    	PreparedStmt.setBigDecimal(Col, BigDecimalValue);
			                    break;
			                case "REAL":
			                case "FLOAT":
			                    FloatValue = SourceResultSetObj.getFloat(Col);
			                    if(SourceResultSetObj.wasNull())
			                    	PreparedStmt.setNull(Col, java.sql.Types.REAL);
			                    else
			                    	PreparedStmt.setFloat(Col, FloatValue);
			                    break;
			                case "TINYBLOB":
			                case "BLOB":
			                case "MEDIUMBLOB":
							case "LONGBLOB":
							case "JSON":	//JSON is internally mapped to LONGBLOB. this case is added here juist in case
			                	BlobObj = SourceResultSetObj.getBytes(Col);
			                	if (BlobObj != null) 
			                		//Use setBytes / getBytes for BLOB
			                		PreparedStmt.setBytes(Col, BlobObj);
			                	else   
			                		PreparedStmt.setNull(Col, java.sql.Types.BLOB);
			                    break;
			                case "SQLXML": PreparedStmt.setClob(Col, SourceResultSetObj.getClob(Col));
			                    break;
			                default: 
			                	ErrorString += "\nUnknown Data Type: " + Table.getFullTableName() + "(" + SourceResultSetMeta.getColumnName(Col) + "(" + SourceResultSetMeta.getColumnTypeName(Col) + "))";
								TableLog.WriteLog(ErrorString);
					        	throw new SQLException(ErrorString);
			            }
			        }

			        PreparedStmt.addBatch();
					BatchRowCounter++;

					//Run Rerun Bath Counter only for Re-Run condition
					if (TmpBatchSize > 0) {
						RerunBatchRowCounter++;
					}

					//Batch has reached its COMMIT point or its the last batch which may
					//be less than the perfect batch size, we still want to process it
			        if (BatchRowCounter % BATCH_SIZE == 0) {
						BatchError=false;

						try {
			        		PreparedStmt.executeBatch();
						} catch (SQLException ex) {
							//Only if its not a RERUN then declare an error to trigger a rerun loop
							if (RerunBatchRowCounter == 0) {
								BatchError = true;
							}
							throw new SQLException(ex);
						} finally {
							//Once Rerun batch is complete, reset the BATCH SIZE back to original value
							if (TmpBatchSize > 0 && RerunBatchRowCounter % TmpBatchSize == 0) {
								System.out.println("\n******************************************************");
								System.out.println("Batch Rerun Completed, Continuing in normal Batch Mode");
								System.out.println("******************************************************");
								BATCH_SIZE = TmpBatchSize;
								RerunBatchRowCounter = 0;
								TmpBatchSize = 0;
							}
						}

						TargetCon.getDBConnection().commit();

						//Only Increase batch counter if its not a RERUN, because that batch has already been counted
						if (TmpBatchSize == 0) {
							BatchCounter++;
							//Total Records Migrated
							CommitCount += BATCH_SIZE;
						}
						BatchRowCounter = 0;

						//Log Progress for the Table
    	                Prog.LogInsertProgress(TotalRecords, CommitCount, CommitCount);

						//This will Recalculate the Batch size based on the last remaining records after the perfect batches have completed
						if (NumberOfBatches == BatchCounter) {
							//Ensure it's not less than equal to ZERO
							BATCH_SIZE = ((TotalRecords - CommitCount) <= 0 ? 1 : (TotalRecords - CommitCount));
						}

						//Don't calculate time for each commit, but wait for 10 batches to re-estimate, don't do this when running multi-threaded
						if (BatchCounter % BATCH_CALC_SIZE == 0 && TmpBatchSize == 0 && !MultiThreaded) {
							//Seconds Taken from Start to Now!
							SecondsTaken = ChronoUnit.SECONDS.between(StartDT, LocalTime.now());
							if (SecondsTaken == 0) {
								SecondsTaken = 1;
							}

							//Time Taken for 1 Record
							TimeforOneRecord = (float)(SecondsTaken/(float)CommitCount);
							RecordsPerSecond = (long)(((float)CommitCount / (float)SecondsTaken));

							//Remaining Seconds based on the "Total Records [take] Already Committed" records
							SecondsRemaining = (long)((TotalRecords-CommitCount) * (TimeforOneRecord));
						}
						ProgressPercent = ((float)CommitCount / (float)TotalRecords * 100f);

						OutString = Util.rPad(LocalTime.now().truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " - Processing " + Table.getFullTableName(), 79, " ") + " --> " + Util.lPad(Util.percentFormat.format(ProgressPercent) + "%", 7, " ") + " [" + Util.lPad(Util.numberFormat.format(CommitCount) + " / " + Util.numberFormat.format(TotalRecords) + " @ " + Util.numberFormat.format(RecordsPerSecond) + "/s", 36, " ") + "]  - ETA       [" + Util.TimeToString(SecondsRemaining) + "]";
						//To Not print progress on a multi-threaded run
						if (!MultiThreaded) {
							System.out.print("\r" + OutString);
						}
						TableLog.WriteLog(OutString);
			        }
				} catch (SQLException e) {
					TargetCon.getDBConnection().rollback();
					new Logger(LogPath + "/" + Table.getFullTableName() + ".err", e.getMessage(), true);
					ErrorString="";
					e.printStackTrace();
				}

				//If there are errors and Retry on Errors is set to YES
				if (BatchError && RetryOnErrors) {
					for (int RevCounter=0; RevCounter < BatchRowCounter; RevCounter++) {
						SourceResultSetObj.previous();
					}
					if (BatchRowCounter > 0) {
						System.out.println("\n***********************************************************************");
						System.out.println("Error in the Batch Execution, Retrying the batch using Single Row Mode!");
						System.out.println("***********************************************************************");
					}
					TmpBatchSize = BATCH_SIZE;
					BatchRowCounter = 0;
					BATCH_SIZE = 1;
					SecondsRemaining=1;
				}
			}
			//Final Output
			OutString = Util.rPad(StartDT.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " - Processing " + Table.getFullTableName(), 79, " ") + " --> 100.00% [" + Util.lPad(Util.numberFormat.format(CommitCount) + " / " + Util.numberFormat.format(TotalRecords) + " @ " + Util.numberFormat.format(RecordsPerSecond) + "/s", 36, " ") + "]  - COMPLETED [" + LocalTime.now().truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "]";

			System.out.println("\r" + OutString);
			TableLog.WriteLog(OutString);

			//Close Statements and ResultSet
	        SourceResultSetObj.close();
        	SourceStatementObj.close();
        	PreparedStmt.close();        	
		} catch (SQLException e) {
        	TargetCon.getDBConnection().rollback();
			new Logger(LogPath + "/" + Table.getFullTableName() + ".err", e.getMessage(), true);
			e.printStackTrace();

		} finally {	//Cleanup	        
	        TargetCon.DisconnectDB();
			SourceCon.DisconnectDB();
			Prog.ProgressEnd();
		}

		TableLog.WriteLog("- EOF -");
		TableLog.CloseLogFile();
		return MigratedRows;
	}
}