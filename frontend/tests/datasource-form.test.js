import { test } from 'node:test'
import assert from 'node:assert/strict'
import { profileTypes, versionsForType, defaultForm, databaseLabel, driversForType, paramRows, buildPayload } from '../src/datasource-form.js'

const profiles = [
  { id: 'MYSQL:5.x', type: 'MYSQL', displayName: 'MySQL 5.x', defaultPort: 3306, extraFields: [], extraParams: [] },
  { id: 'MYSQL:8.x', type: 'MYSQL', displayName: 'MySQL 8.x', defaultPort: 3306, extraFields: [], extraParams: [{ key: 'serverTimezone' }] },
  { id: 'ORACLE:11g', type: 'ORACLE', displayName: 'Oracle 11g', defaultPort: 1521, extraFields: [{ key: 'connectType', label: '连接方式', options: ['SID', 'SERVICE'], defaultValue: 'SID' }], extraParams: [] },
  { id: 'ORACLE:12c+', type: 'ORACLE', displayName: 'Oracle 12c 及以上', defaultPort: 1521, extraFields: [{ key: 'connectType', label: '连接方式', options: ['SID', 'SERVICE'], defaultValue: 'SERVICE' }], extraParams: [] }
]

test('profileTypes deduplicates with labels', () => {
  assert.deepEqual(profileTypes(profiles), [{ value: 'MYSQL', label: 'MySQL' }, { value: 'ORACLE', label: 'Oracle' }])
  assert.deepEqual(profileTypes([]), [])
  assert.deepEqual(profileTypes(null), [])
})
test('versionsForType filters', () => {
  assert.deepEqual(versionsForType(profiles, 'MYSQL').map(p => p.id), ['MYSQL:5.x', 'MYSQL:8.x'])
  assert.deepEqual(versionsForType(profiles, 'DB2'), [])
})
test('defaultForm carries port and extra field defaults', () => {
  assert.deepEqual(defaultForm(profiles[2]), { profileId: 'ORACLE:11g', port: 1521, options: { connectType: 'SID' } })
  assert.deepEqual(defaultForm(profiles[0]), { profileId: 'MYSQL:5.x', port: 3306, options: {} })
})
test('databaseLabel follows oracle connect type', () => {
  assert.equal(databaseLabel(profiles[2], {}), 'SID')
  assert.equal(databaseLabel(profiles[3], { connectType: 'SID' }), 'SID')
  assert.equal(databaseLabel(profiles[3], {}), '服务名')
  assert.equal(databaseLabel(profiles[0], {}), '数据库')
})
test('driversForType filters by dbType', () => {
  assert.deepEqual(driversForType([{ id: 1, dbType: 'MYSQL' }, { id: 2, dbType: 'ORACLE' }], 'MYSQL'), [{ id: 1, dbType: 'MYSQL' }])
  assert.deepEqual(driversForType(null, 'MYSQL'), [])
})
test('paramRows excludes extra fields and empty values', () => {
  assert.deepEqual(paramRows(profiles[2], { connectType: 'SID', 'oracle.jdbc.ReadTimeout': '10000' }), [{ key: 'oracle.jdbc.ReadTimeout', value: '10000' }])
  assert.deepEqual(paramRows(profiles[0], { serverTimezone: '' }), [])
})
test('buildPayload nulls blank password on edit and drops empty options', () => {
  const form = { name: 'n', profileId: 'MYSQL:8.x', host: 'h', port: 3306, database: 'd', username: 'u', password: '', driverId: null, options: { connectType: 'SID', serverTimezone: '' } }
  const payload = buildPayload(form, true)
  assert.equal(payload.password, null)
  assert.deepEqual(payload.options, { connectType: 'SID' })
  assert.equal(payload.driverId, null)
  assert.equal(buildPayload({ ...form, password: 'x' }, false).password, 'x')
  assert.equal(buildPayload({ ...form, password: '' }, false).password, '')
  assert.equal(buildPayload({ ...form, driverId: 7 }, true).driverId, 7)
})
