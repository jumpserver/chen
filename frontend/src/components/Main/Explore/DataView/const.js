export const GeneralUpdateSQL = 'UPDATE {table_name} SET {updated_attrs} WHERE {conditional_attrs};'
export const GeneralInsertSQL = 'INSERT INTO {table_name} ({fields}) VALUES ({values});'

export const SpecialCharacters = {
  mysql: '`',
  mariadb: '`',
  postgresql: '"',
  sqlserver: '',
  oracle: '"',
  dameng: '"',
  db2: ''
}
