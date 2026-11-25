export function dictionaryField(entry, sourceType) {
  const mappings = {
    MYSQL: { varchar:'VARCHAR', char:'CHAR', integer:'INT', bigint:'BIGINT', decimal:'DECIMAL', date:'DATE', datetime:'DATETIME' },
    POSTGRESQL: { varchar:'VARCHAR', char:'CHAR', integer:'INTEGER', bigint:'BIGINT', decimal:'NUMERIC', date:'DATE', datetime:'TIMESTAMP' },
    ORACLE: { varchar:'VARCHAR2', char:'CHAR', integer:'NUMBER', bigint:'NUMBER', decimal:'NUMBER', date:'DATE', datetime:'TIMESTAMP' },
  }
  const type = mappings[sourceType]?.[entry.dataType]
  if (!type) return { error: `字典类型 ${entry.dataType} 暂不能安全映射到当前数据源` }
  const isCharacter = ['varchar','char'].includes(entry.dataType)
  const isDecimal = entry.dataType === 'decimal'
  const oracleInteger = sourceType === 'ORACLE' && ['integer','bigint'].includes(entry.dataType)
  return { field: { name:entry.code, type, length:oracleInteger ? (entry.dataType==='integer'?10:19) : (isCharacter||isDecimal ? entry.length : null), scale:oracleInteger ? 0 : (isDecimal ? entry.precision : null), nullable:true, primaryKey:false, remarks:entry.meaning || entry.name || '' }, needsLength:isCharacter && !entry.length }
}
