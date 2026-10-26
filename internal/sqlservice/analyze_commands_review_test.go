package sqlservice

import "testing"

// T-SQL permits adjoining statements without semicolons. The bounded scanner
// must reject ambiguous batches rather than hiding later commands in one ACL
// check or dispatching a mixed result-producing batch through ExecContext.
func TestReviewTSQLImplicitBatchBoundaries(t *testing.T) {
	for _, query := range []string{
		"INSERT INTO t(id) VALUES(1) SELECT secret FROM s",
		"CREATE TABLE t(id int) SELECT secret FROM s",
		"GRANT SELECT ON t TO reader DELETE FROM t",
		"ALTER TABLE t ADD c int DROP TABLE other",
		"ALTER TABLE t ADD c int DROP DEFAULT old_default",
		"UPDATE t SET n=1 SET NOCOUNT ON",
		"SELECT 1 PRINT 'second statement'",
		"SELECT 1 WAITFOR DELAY '00:00:00.001'",
		"SELECT 1 DBCC CHECKIDENT ('t', RESEED, 1)",
		"SELECT 1 KILL 53",
		"SELECT 1 DECLARE @value int",
		"SELECT 1 IF 1=1 PRINT 'nested statement'",
		"SELECT 1 RAISERROR ('message', 10, 1)",
		"SELECT 1 DENY SELECT ON t TO reader",
		"SELECT 1 BACKUP DATABASE db TO DISK='file.bak'",
		"SELECT 1 SAVE TRANSACTION hidden",
		"SELECT 1 OPEN hidden_cursor",
		"SELECT 1 FETCH NEXT FROM hidden_cursor",
		"SELECT 1 CHECKPOINT",
		"SELECT 1 SETUSER 'dbo'",
		"SELECT 1\nGO 3 -- repeat",
		"ALTER TABLE t ALTER COLUMN n int ALTER USER reader WITH DEFAULT_SCHEMA=dbo",
		"CREATE SCHEMA s AUTHORIZATION dbo CREATE TABLE s.hidden(id int)",
		"CREATE OR ALTER PROCEDURE p AS SELECT 1",
	} {
		t.Run(query, func(t *testing.T) {
			if analysis, err := Analyze(SQLServer, query); err == nil {
				t.Fatalf("implicit batch is not individually policy-visible: %+v", analysis)
			}
		})
	}
}

func TestReviewSupportedOrdinaryDDLAndReadsRemainAvailable(t *testing.T) {
	for _, query := range []string{
		"CREATE TABLE [app].[t] ([id] INT PRIMARY KEY, [name] NVARCHAR(50))",
		"CREATE UNIQUE INDEX [i] ON [app].[t] ([name])",
		"ALTER TABLE [app].[t] ADD [note] NVARCHAR(50)",
		"ALTER TABLE [app].[t] ALTER COLUMN [note] NVARCHAR(60) NULL",
		"ALTER TABLE [app].[t] DROP COLUMN [note]",
		"ALTER TABLE [app].[t] DROP CONSTRAINT [constraint_name]",
		"DROP INDEX [i] ON [app].[t]",
		"DROP TABLE IF EXISTS [app].[t]",
		"INSERT INTO [app].[t] ([id]) SELECT [id] FROM [app].[s] UNION ALL SELECT [id] FROM [app].[s2]",
		"UPDATE [app].[t] SET [name]='changed' WHERE [id]=1",
		"SELECT CASE WHEN [id]>0 THEN 'yes' ELSE 'no' END AS [status] FROM [app].[t] ORDER BY [id] DESC OFFSET 0 ROWS FETCH NEXT 20 ROWS ONLY",
		"WITH chosen AS (SELECT [id] FROM [app].[t]) SELECT [id] FROM chosen",
		"SELECT [id] FROM [app].[t] WHERE EXISTS (SELECT 1 FROM [app].[s] WHERE [s].[id]=[t].[id])",
		"SAVE TRANSACTION [named_savepoint]",
		"BEGIN TRANSACTION",
		"ROLLBACK TRANSACTION [named_savepoint]",
		"COMMIT TRANSACTION",
	} {
		t.Run(query, func(t *testing.T) {
			analysis, err := Analyze(SQLServer, query)
			if err != nil || len(analysis.Statements) != 1 {
				t.Fatalf("ordinary SQL rejected: %+v %v", analysis, err)
			}
		})
	}
}

func TestReviewExportReadGateRejectsControlStatements(t *testing.T) {
	for _, query := range []string{
		"SELECT 1 KILL 53",
		"SELECT 1 DBCC CHECKIDENT ('t', RESEED, 1)",
		"SELECT 1 WAITFOR DELAY '00:00:01'",
		"SELECT 1 DENY SELECT ON t TO reader",
	} {
		if analysis, err := analyzeSelectDocument(SQLServer, query, AnalyzeOptions{}); err == nil {
			t.Errorf("strict export/estimated-plan read gate accepted %q: %+v", query, analysis)
		}
	}
}

func TestReviewTSQLValidOutputDispatch(t *testing.T) {
	for _, query := range []string{
		"DELETE FROM t OUTPUT deleted.id WHERE id=1",
		"INSERT INTO t(id) OUTPUT inserted.id SELECT id FROM s",
		"UPDATE t SET n=1 OUTPUT inserted.id, deleted.n WHERE id=1",
	} {
		analysis, err := Analyze(SQLServer, query)
		if err != nil || len(analysis.Statements) != 1 || !analysis.Statements[0].ReturnsRows || !analysis.Statements[0].MayWrite {
			t.Errorf("OUTPUT result dispatch for %q: %+v %v", query, analysis, err)
		}
	}
}
