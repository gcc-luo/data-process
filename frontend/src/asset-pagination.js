/** The current asset APIs return complete collections; pagination is local to this view. */
export function assetPage(items, search, page, size) {
  const text = String(search ?? '').trim().toLocaleLowerCase()
  const rows = items.filter(item => !text || [item.name, item.host, item.database, item.schema, item.table, item.type, item.dbType, item.driverClass].some(value => String(value ?? '').toLocaleLowerCase().includes(text)))
  const width = Number.isInteger(size) && size > 0 ? size : 10
  const current = Math.max(1, Math.min(Number.isInteger(page) ? page : 1, Math.ceil(rows.length / width) || 1))
  return { rows: rows.slice((current - 1) * width, current * width), total: rows.length, page: current }
}
