const CHUNK_SIZE = 4096

function createRequestId() {
  if (typeof window !== 'undefined' &&
      window.crypto &&
      typeof window.crypto.randomUUID === 'function') {
    return window.crypto.randomUUID()
  }

  let seed = Date.now()
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (character) => {
    const value = (seed + Math.random() * 16) % 16 | 0
    seed = Math.floor(seed / 16)
    return (character === 'x' ? value : (value & 0x3) | 0x8).toString(16)
  })
}

function buildSQLRunActions(sql, requestIdFactory = createRequestId) {
  if (sql.length <= CHUNK_SIZE) {
    return [{ action: 'run_sql', data: sql }]
  }

  const requestId = requestIdFactory()
  const total = Math.ceil(sql.length / CHUNK_SIZE)
  const actions = []
  for (let index = 0; index < total; index++) {
    actions.push({
      action: 'run_sql_chunk',
      data: {
        requestId,
        chunk: sql.slice(index * CHUNK_SIZE, (index + 1) * CHUNK_SIZE),
        index,
        total
      }
    })
  }
  actions.push({
    action: 'run_sql_complete',
    data: { requestId, total }
  })
  return actions
}

module.exports = {
  CHUNK_SIZE,
  buildSQLRunActions,
  createRequestId
}

