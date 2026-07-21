const { format } = require('sql-formatter')

// Front-end formatting and highlighting only. Dameng has no dedicated dialect
// in these libraries, so PLSQL is the best-effort fallback for editor assistance.
const formatterLanguageMap = {
  clickhouse: 'sql',
  mariadb: 'mariadb',
  mysql: 'mysql',
  postgresql: 'postgresql',
  oracle: 'plsql',
  sqlserver: 'tsql',
  db2: 'db2',
  dameng: 'plsql'
}

const editorModeMap = {
  clickhouse: 'text/x-sql',
  mariadb: 'text/x-mariadb',
  mysql: 'text/x-mysql',
  postgresql: 'text/x-pgsql',
  oracle: 'text/x-plsql',
  sqlserver: 'text/x-mssql',
  db2: 'text/x-sql',
  dameng: 'text/x-plsql'
}

function getFormatterLanguage(databaseType) {
  return Object.prototype.hasOwnProperty.call(formatterLanguageMap, databaseType)
    ? formatterLanguageMap[databaseType]
    : 'sql'
}

function getEditorMode(databaseType) {
  return Object.prototype.hasOwnProperty.call(editorModeMap, databaseType)
    ? editorModeMap[databaseType]
    : 'text/x-sql'
}

function formatSqlForEditor(originalSql, databaseType, formatter = format) {
  try {
    return formatter(originalSql, {
      language: getFormatterLanguage(databaseType)
    })
  } catch {
    return originalSql
  }
}

module.exports = {
  editorModeMap,
  formatSqlForEditor,
  formatterLanguageMap,
  getEditorMode,
  getFormatterLanguage
}
