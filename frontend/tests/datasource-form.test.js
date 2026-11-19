import { test } from 'node:test'
import assert from 'node:assert/strict'
import { profileTypes, versionsForType, defaultForm, databaseLabel, driversForType, paramRows, buildPayload, connectionKey, saveAllowed, testOutcome } from '../src/datasource-form.js'

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

const draft = { id: null, name: 'n', profileId: 'MYSQL:8.x', host: 'h', port: 3306, database: 'd', username: 'u', password: 'p', driverId: null, options: { serverTimezone: 'UTC', connectTimeout: '5000' } }

test('connectionKey ignores name and option order', () => {
  const reordered = { ...draft, name: '改名', options: { connectTimeout: '5000', serverTimezone: 'UTC' } }
  assert.equal(connectionKey(draft), connectionKey(reordered))
})
test('connectionKey changes when any connection field changes', () => {
  const base = connectionKey(draft)
  for (const patch of [{ host: 'other' }, { port: 3307 }, { database: 'x' }, { username: 'x' }, { password: 'x' }, { password: null }, { profileId: 'MYSQL:5.x' }, { driverId: 3 }, { id: 9 }, { options: { serverTimezone: 'Asia/Shanghai' } }]) {
    assert.notEqual(connectionKey({ ...draft, ...patch }), base, JSON.stringify(patch))
  }
})
test('saveAllowed requires a passing test for the exact current configuration', () => {
  assert.equal(saveAllowed('', draft), false)
  assert.equal(saveAllowed(null, draft), false)
  const passed = connectionKey(draft)
  assert.equal(saveAllowed(passed, draft), true)
  assert.equal(saveAllowed(passed, { ...draft, name: '只改名字' }), true)
  assert.equal(saveAllowed(passed, { ...draft, host: 'changed' }), false)
  assert.equal(saveAllowed(passed, { ...draft, password: '' }), false)
})

test('batchDeleteSummary reports deleted count and per-item failures', async () => {
  const { batchDeleteSummary } = await import('../src/datasource-form.js')
  const items = [{ id: 1, name: '订单库' }, { id: 2, name: '用户库' }, { id: 3, name: '日志库' }]
  assert.deepEqual(batchDeleteSummary({ deleted: [1], failed: [{ id: 2, reason: 'REFERENCED' }, { id: 3, reason: 'NOT_FOUND' }] }, items), { message: '已删除 1 个数据源', error: '以下数据源未删除：用户库（被引用，无法删除）、日志库（不存在）' })
  assert.deepEqual(batchDeleteSummary({ deleted: [1, 2], failed: [] }, items), { message: '已删除 2 个数据源', error: '' })
  assert.deepEqual(batchDeleteSummary({ deleted: [], failed: [{ id: 9, reason: 'REFERENCED' }] }, items), { message: '', error: '以下数据源未删除：编号 9（被引用，无法删除）' })
  assert.deepEqual(batchDeleteSummary(null, null), { message: '', error: '' })
})

test('testOutcome surfaces the driver error for the popup', () => {
  assert.deepEqual(testOutcome({ connected: true }), { ok: true, message: '连接成功' })
  assert.deepEqual(testOutcome({ connected: false, error: "Access denied for user 'reader'" }), { ok: false, message: "Access denied for user 'reader'" })
  assert.deepEqual(testOutcome({ connected: false }), { ok: false, message: '连接失败，请检查配置' })
  assert.deepEqual(testOutcome(null), { ok: false, message: '连接失败，请检查配置' })
})

test('driverLabel marks builtin and uploaded drivers with version', async () => {
  const { driverLabel } = await import('../src/datasource-form.js')
  assert.equal(driverLabel({ name: 'MySQL Connector/J', versionLabel: '9.5', builtin: true }), 'MySQL Connector/J 9.5（内置）')
  assert.equal(driverLabel({ name: 'ojdbc6', versionLabel: '', builtin: false }), 'ojdbc6（上传）')
  assert.equal(driverLabel({ name: 'pg', versionLabel: null, builtin: false }), 'pg（上传）')
})
