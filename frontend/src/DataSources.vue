<script setup>
import { computed, inject, onMounted, ref, watch } from 'vue'
import { assetPage } from './asset-pagination.js'
import { authorizedRequest } from './auth.js'
import { profileTypes, versionsForType, defaultForm, databaseLabel, driversForType, paramRows, buildPayload } from './datasource-form.js'
const can = inject('canPermission', () => false)
const props = defineProps({ view: { type: String, default: 'sources' } })
const items = ref([]), error = ref(''), message = ref(''), busy = ref(false), registering = ref(false), show = ref(false)
const datasets = ref([]), tables = ref([]), selectedSource = ref(null), schema = ref(''), datasetName = ref('')
const policyOpen = ref(false), policyDataset = ref(null), roleOptions = ref([]), policyForm = ref({ departmentField: '', roleId: null, scope: 'OWN', fields: {} })
const policyRules = ref([]), storedDepartmentField = ref('')
const sourceSearch = ref(''), datasetSearch = ref(''), tableSearch = ref('')
const sourcePage = ref(1), datasetPage = ref(1), tablePage = ref(1), sourceSize = ref(10), datasetSize = ref(10), tableSize = ref(10)
const sourcesPage = computed(() => assetPage(items.value, sourceSearch.value, sourcePage.value, sourceSize.value))
const datasetsPage = computed(() => assetPage(datasets.value, datasetSearch.value, datasetPage.value, datasetSize.value))
const tablesPage = computed(() => assetPage(tables.value, tableSearch.value, tablePage.value, tableSize.value))
const catalogOpen = ref(false), fieldsOpen = ref(false), fieldsDataset = ref(null), catalogSourceId = ref(null)
let inspectSequence = 0
const editingId = ref(null)
const form = ref({ name: '', type: 'MYSQL', profileId: '', host: '', port: 3306, database: '', username: '', password: '', driverId: null, connectType: 'SERVICE' })
const profiles = ref([]), drivers = ref([]), advanced = ref([])
const driverSearch = ref(''), driverPage = ref(1), driverSize = ref(10)
const uploadOpen = ref(false), uploading = ref(false)
const uploadForm = ref({ name: '', dbType: 'MYSQL', driverClass: '', versionLabel: '', file: null })
const typeOptions = computed(() => profileTypes(profiles.value))
const versionOptions = computed(() => versionsForType(profiles.value, form.value.type))
const currentProfile = computed(() => profiles.value.find(p => p.id === form.value.profileId))
const typeDrivers = computed(() => driversForType(drivers.value, form.value.type))
const dbLabel = computed(() => databaseLabel(currentProfile.value, { connectType: form.value.connectType }))
const hasConnectType = computed(() => (currentProfile.value?.extraFields || []).some(f => f.key === 'connectType'))
const driversPage = computed(() => assetPage(drivers.value, driverSearch.value, driverPage.value, driverSize.value))
function applyProfile(profile) {
  if (!profile) return
  const defaults = defaultForm(profile)
  form.value.profileId = defaults.profileId
  form.value.port = defaults.port
  form.value.connectType = defaults.options.connectType || 'SERVICE'
  form.value.driverId = null
  advanced.value = []
}
function selectType(type) {
  form.value.type = type
  applyProfile(versionsForType(profiles.value, type)[0])
}
function selectProfile(profileId) {
  applyProfile(profiles.value.find(p => p.id === profileId))
}
async function loadProfiles() {
  if (!can('datasource.source.read')) { profiles.value = []; return }
  try {
    const response = await fetch('/api/data-sources/profiles', { credentials: 'same-origin' })
    if (response.ok) profiles.value = await response.json()
  } catch { /* 表单目录加载失败时保留空列表，保存仍会被后端校验 */ }
}
async function loadDrivers() {
  if (!can('datasource.driver.read')) { drivers.value = []; return }
  try {
    const response = await fetch('/api/drivers', { credentials: 'same-origin' })
    if (!response.ok) throw new Error(response.status === 403 ? '当前账号没有驱动管理权限' : '无法读取驱动列表')
    drivers.value = await response.json()
  } catch (e) { error.value = e.message }
}
function openUpload() {
  error.value = ''
  uploadForm.value = { name: '', dbType: form.value.type || 'MYSQL', driverClass: '', versionLabel: '', file: null }
  uploadOpen.value = true
}
async function submitUpload() {
  if (uploading.value) return
  if (!uploadForm.value.file) { error.value = '请选择 jar 文件'; return }
  if (!uploadForm.value.name.trim() || !uploadForm.value.driverClass.trim()) { error.value = '请填写驱动名称和驱动类'; return }
  uploading.value = true; error.value = ''
  try {
    const data = new FormData()
    data.append('file', uploadForm.value.file)
    data.append('name', uploadForm.value.name.trim())
    data.append('dbType', uploadForm.value.dbType)
    data.append('driverClass', uploadForm.value.driverClass.trim())
    if (uploadForm.value.versionLabel.trim()) data.append('versionLabel', uploadForm.value.versionLabel.trim())
    const response = await authorizedRequest('/api/drivers', { method: 'POST', body: data })
    if (!response.ok) {
      const payload = await response.json().catch(() => null)
      throw new Error(payload?.error || '上传失败，请检查文件与权限')
    }
    uploadOpen.value = false
    message.value = '驱动已上传'
    await loadDrivers()
  } catch (e) { error.value = e.message } finally { uploading.value = false }
}
async function deleteDriver(item) {
  error.value = ''
  try {
    const response = await authorizedRequest(`/api/drivers/${item.id}`, { method: 'DELETE' })
    if (!response.ok) {
      const payload = await response.json().catch(() => null)
      if (response.status === 409) throw new Error(`驱动被数据源引用：${(payload?.references || []).join('、')}`)
      throw new Error(payload?.error || '删除失败，请刷新后重试')
    }
    await loadDrivers()
  } catch (e) { error.value = e.message }
}
async function load() {
  error.value = ''
  if (!can('datasource.source.read')) { items.value = []; return }
  try {
    const response = await fetch('/api/data-sources', { credentials: 'same-origin' })
    if (!response.ok) throw new Error(response.status === 403 ? '当前账号没有数据源管理权限' : '无法读取数据源')
    items.value = await response.json()
  } catch (e) { error.value = e.message }
}
async function deleteSource(item) {
  error.value = ''
  try {
    const response = await authorizedRequest(`/api/data-sources/${item.id}`, { method: 'DELETE' })
    if (!response.ok) throw new Error(response.status === 409 ? '数据源被数据集、实时任务或历史任务版本引用，或历史配置无法安全检查，无法删除' : response.status === 403 ? '当前账号没有数据源管理权限' : '删除失败，请刷新后重试')
    if (selectedSource.value?.id === item.id) { selectedSource.value = null; tables.value = []; catalogOpen.value = false }
    await load()
  } catch (e) { error.value = e.message }
}
async function testConnection(item) {
  error.value = ''
  try {
    const response = await authorizedRequest(`/api/data-sources/${item.id}/test`, { method: 'POST' })
    if (!response.ok) throw new Error('连接测试请求失败')
    const result = await response.json()
    item.testStatus = result.connected ? '连接成功' : '连接失败，请检查配置'
  } catch (e) { error.value = e.message }
}
async function loadDatasets() {
  if (!can('datasource.dataset.read')) { datasets.value = []; return }
  const response = await fetch('/api/datasets', { credentials: 'same-origin' })
  if (!response.ok) throw new Error('无法读取数据集')
  datasets.value = await response.json()
}
async function refreshDatasetList() {
  try { error.value = ''; await loadDatasets() } catch (e) { error.value = e.message }
}
async function refreshDataset(item) {
  error.value = ''
  try {
    const response = await authorizedRequest(`/api/datasets/${item.id}/refresh`, { method: 'POST' })
    if (!response.ok) throw new Error(response.status === 409 ? '已有字段已更改或删除，请先处理相关授权、报表和服务' : '重新采集失败，请检查连接和表结构')
    const result = await response.json(); await loadDatasets()
    const updated = datasets.value.find(d => d.id === item.id); if (!updated) return
    if (result.added) updated.refreshStatus = `新增 ${result.added} 个字段，需单独授权`
    else updated.refreshStatus = '没有新增字段（未检查长度和精度变化）'
  } catch (e) { error.value = e.message }
}
async function deleteDataset(item) {
  error.value = ''
  try {
    const response = await authorizedRequest(`/api/datasets/${item.id}`, { method: 'DELETE' })
    if (!response.ok) throw new Error(response.status === 409 ? '数据集仍被权限策略、报表或数据服务引用，不能删除' : response.status === 403 ? '当前账号没有数据源管理权限' : '删除数据集失败，请刷新后重试')
    if (policyDataset.value?.id === item.id) { policyOpen.value = false; policyDataset.value = null }
    await loadDatasets()
  } catch (e) { error.value = e.message }
}
async function openCatalog(item = null) {
  schema.value = ''; datasetName.value = ''; tableSearch.value = ''; tablePage.value = 1; tables.value = []; selectedSource.value = null
  catalogSourceId.value = item?.id ?? null; catalogOpen.value = true
  if (!items.value.length) await load()
  if (item) await inspect(item)
}
async function selectCatalogSource(id) {
  const item = items.value.find(source => source.id === id)
  if (item) await inspect(item)
  else { inspectSequence++; selectedSource.value = null; tables.value = [] }
}
async function inspect(item) {
  const requestId = ++inspectSequence
  selectedSource.value = item; tables.value = []; tablePage.value = 1; error.value = ''
  try {
    const query = schema.value ? `?schema=${encodeURIComponent(schema.value)}` : ''
    const response = await fetch(`/api/data-sources/${item.id}/tables${query}`, { credentials: 'same-origin' })
    if (!response.ok) throw new Error('无法读取表，请检查连接和 schema')
    const result = await response.json()
    if (requestId === inspectSequence) tables.value = result
  } catch (e) { if (requestId === inspectSequence) error.value = e.message }
}
async function register(table) {
  if (registering.value) return
  if (!datasetName.value.trim()) { error.value = '请填写数据集名称'; return }
  registering.value = true; error.value = ''; message.value = ''
  try {
    const response = await authorizedRequest('/api/datasets', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ sourceId: selectedSource.value.id, name: datasetName.value, catalog: table.catalog, schema: table.schema, table: table.name }) })
    if (!response.ok) throw new Error('登记失败，请检查权限、名称和数据库连接')
    datasetName.value = ''; catalogOpen.value = false
    message.value = '数据集已登记'
    try { await loadDatasets() } catch { error.value = '数据集已登记，但列表刷新失败，请手动刷新' }
  } catch (e) { error.value = e.message } finally { registering.value = false }
}
function selectPolicyRole(roleId) {
  const rule = policyRules.value.find(r => r.roleId === roleId)
  policyForm.value.scope = rule?.scope ?? 'OWN'
  policyForm.value.fields = Object.fromEntries((policyDataset.value?.fields ?? []).map(f => [f.name, rule?.fields[f.name] ?? 'NONE']))
  policyForm.value.departmentField = storedDepartmentField.value || policyForm.value.departmentField
}
async function refreshPolicies() {
  const response = await fetch(`/api/datasets/${policyDataset.value.id}/policies`, { credentials: 'same-origin' })
  if (!response.ok) throw new Error('无法读取数据集权限，请检查权限和数据集状态')
  const policy = await response.json(); policyRules.value = policy.rules; storedDepartmentField.value = policy.departmentField ?? ''
}
async function openPolicy(dataset) {
  policyDataset.value = dataset; policyRules.value = []; storedDepartmentField.value = ''
  policyForm.value = { departmentField: '', roleId: null, scope: 'OWN', fields: Object.fromEntries(dataset.fields.map(f => [f.name, 'NONE'])) }
  try {
    await refreshPolicies()
    policyForm.value.departmentField = storedDepartmentField.value
    const response = await fetch('/api/organization', { credentials: 'same-origin' })
    roleOptions.value = response.ok ? (await response.json()).roles : []
    policyOpen.value = true; error.value = ''
  } catch (e) { error.value = e.message }
}
async function revokePolicy() {
  try {
    const response = await authorizedRequest(`/api/datasets/${policyDataset.value.id}/policies/${policyForm.value.roleId}`, { method: 'DELETE' })
    if (!response.ok) throw new Error('撤销权限失败，请刷新后重试')
    await refreshPolicies(); selectPolicyRole(policyForm.value.roleId); error.value = ''
  } catch (e) { error.value = e.message }
}
async function savePolicy() {
  error.value = ''
  const fields = Object.fromEntries(Object.entries(policyForm.value.fields).filter(([, mode]) => mode !== 'NONE'))
  try {
    const response = await authorizedRequest(`/api/datasets/${policyDataset.value.id}/policies`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...policyForm.value, fields }) })
    if (!response.ok) throw new Error(response.status === 409 ? '其他角色正在使用原部门字段，请先撤销其授权' : '权限保存失败：请选择数字类型部门字段、角色和至少一个字段')
    policyOpen.value = false
  } catch (e) { error.value = e.message }
}
function showFields(dataset) { fieldsDataset.value = dataset; fieldsOpen.value = true }
function createSource() {
  error.value = ''; editingId.value = null
  form.value = { name: '', type: 'MYSQL', profileId: '', host: '', port: 3306, database: '', username: '', password: '', driverId: null, connectType: 'SERVICE' }
  advanced.value = []
  const mysql8 = profiles.value.find(p => p.id === 'MYSQL:8.x') || profiles.value[0]
  if (mysql8) { form.value.type = mysql8.type; applyProfile(mysql8) }
  show.value = true
}
function editSource(item) {
  error.value = ''; editingId.value = item.id
  const profile = profiles.value.find(p => p.id === item.profileId)
  form.value = { name: item.name, type: item.type, profileId: item.profileId, host: item.host, port: item.port, database: item.database, username: item.username, password: '', driverId: item.driverId || null, connectType: item.options?.connectType || defaultForm(profile).options.connectType || 'SERVICE' }
  advanced.value = paramRows(profile, item.options)
  show.value = true
}
async function save() {
  busy.value = true; error.value = ''
  try {
    const options = Object.fromEntries(advanced.value.filter(r => r.key).map(r => [r.key, r.value]))
    if (hasConnectType.value) options.connectType = form.value.connectType
    const payload = buildPayload({ ...form.value, options }, Boolean(editingId.value))
    const response = await authorizedRequest(editingId.value ? `/api/data-sources/${editingId.value}` : '/api/data-sources', { method: editingId.value ? 'PUT' : 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) })
    if (!response.ok) throw new Error(response.status === 403 ? '当前账号没有数据源管理权限' : '保存失败，请检查配置和名称是否重复')
    form.value.password = ''; show.value = false; await load()
  } catch (e) { error.value = e.message }
  finally { busy.value = false }
}
async function loadForView() {
  error.value = ''
  if (props.view === 'sources') { await loadProfiles(); await load() }
  else if (props.view === 'drivers') await loadDrivers()
  else { try { await loadDatasets() } catch (e) { error.value = e.message } }
}
onMounted(loadForView)
watch(() => props.view, loadForView)
</script>
<template>
  <section>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-alert v-if="message" :title="message" type="success" :closable="false" show-icon />
    <template v-if="view === 'sources'">
        <div class="toolbar"><el-input v-model="sourceSearch" clearable placeholder="搜索名称、主机或数据库" @input="sourcePage = 1"/><el-button v-if="can('datasource.source.create')" type="primary" @click="createSource">新建数据源</el-button><el-button @click="load">刷新</el-button></div>
        <el-table :data="sourcesPage.rows" empty-text="暂无数据源" row-key="id">
          <el-table-column prop="name" label="名称" min-width="150"/><el-table-column prop="type" label="类型" min-width="100"/><el-table-column prop="profileName" label="版本" min-width="120"/><el-table-column prop="host" label="主机" min-width="150"/><el-table-column prop="port" label="端口" width="85"/><el-table-column prop="database" label="数据库 / 服务名" min-width="150"/><el-table-column prop="username" label="用户名" min-width="110"/>
          <el-table-column label="操作" min-width="300" fixed="right"><template #default="scope"><el-button v-if="can('datasource.source.test')" text type="primary" @click="testConnection(scope.row)">测试连接</el-button><el-button v-if="can('datasource.source.test')" text @click="openCatalog(scope.row)">查看表</el-button><el-button v-if="can('datasource.source.update')" text @click="editSource(scope.row)">编辑</el-button><el-popconfirm v-if="can('datasource.source.delete')" :title="`确定删除数据源 ${scope.row.name}？`" @confirm="deleteSource(scope.row)"><template #reference><el-button text type="danger">删除</el-button></template></el-popconfirm><div class="status-text">{{ scope.row.testStatus }}</div></template></el-table-column>
        </el-table>
        <div class="pagination"><el-pagination :current-page="sourcesPage.page" :page-size="sourceSize" :page-sizes="[10, 20, 50]" :total="sourcesPage.total" layout="total, sizes, prev, pager, next" @current-change="sourcePage = $event" @size-change="sourceSize = $event; sourcePage = 1"/></div>
    </template>
    <template v-else-if="view === 'datasets'">
        <div class="toolbar"><el-input v-model="datasetSearch" clearable placeholder="搜索数据集名称或表名" @input="datasetPage = 1"/><el-button v-if="can('datasource.dataset.create') && can('datasource.source.read') && can('datasource.source.test')" type="primary" @click="openCatalog()">登记数据集</el-button><el-button @click="refreshDatasetList">刷新</el-button></div>
        <el-table :data="datasetsPage.rows" empty-text="暂无数据集" row-key="id">
          <el-table-column prop="name" label="名称" min-width="150"/><el-table-column prop="sourceId" label="数据源编号" width="110"/><el-table-column prop="schema" label="Schema" min-width="120"/><el-table-column prop="table" label="表名" min-width="150"/><el-table-column label="字段" width="90"><template #default="scope">{{ scope.row.fields.length }} 个</template></el-table-column>
          <el-table-column label="操作" min-width="310" fixed="right"><template #default="scope"><el-button v-if="can('datasource.dataset.read')" text @click="showFields(scope.row)">字段详情</el-button><el-button v-if="can('datasource.dataset.manage_policy')" text type="primary" @click="openPolicy(scope.row)">数据范围</el-button><el-button v-if="can('datasource.dataset.refresh')" text @click="refreshDataset(scope.row)">重新采集</el-button><el-popconfirm v-if="can('datasource.dataset.delete')" :title="`确定删除数据集 ${scope.row.name} 的登记信息？业务表不会删除。`" @confirm="deleteDataset(scope.row)"><template #reference><el-button text type="danger">删除</el-button></template></el-popconfirm><div class="status-text">{{ scope.row.refreshStatus }}</div></template></el-table-column>
        </el-table>
        <div class="pagination"><el-pagination :current-page="datasetsPage.page" :page-size="datasetSize" :page-sizes="[10, 20, 50]" :total="datasetsPage.total" layout="total, sizes, prev, pager, next" @current-change="datasetPage = $event" @size-change="datasetSize = $event; datasetPage = 1"/></div>
    </template>
    <template v-else-if="view === 'drivers'">
        <div class="toolbar"><el-input v-model="driverSearch" clearable placeholder="搜索驱动名称或类名" @input="driverPage = 1"/><el-button v-if="can('datasource.driver.upload')" type="primary" @click="openUpload">上传驱动</el-button><el-button @click="loadDrivers">刷新</el-button></div>
        <el-table :data="driversPage.rows" empty-text="暂无驱动" row-key="id">
          <el-table-column prop="name" label="名称" min-width="170"/>
          <el-table-column prop="dbType" label="类型" width="110"/>
          <el-table-column prop="driverClass" label="驱动类" min-width="190"/>
          <el-table-column prop="versionLabel" label="版本" width="90"/>
          <el-table-column label="来源" width="90"><template #default="scope"><el-tag :type="scope.row.builtin ? 'info' : 'success'" size="small">{{ scope.row.builtin ? '内置' : '上传' }}</el-tag></template></el-table-column>
          <el-table-column label="SHA-256" width="130"><template #default="scope"><span class="status-text">{{ (scope.row.sha256 || '').slice(0, 12) }}…</span></template></el-table-column>
          <el-table-column label="操作" width="100" fixed="right"><template #default="scope"><el-popconfirm v-if="can('datasource.driver.delete') && !scope.row.builtin" :title="`确定删除驱动 ${scope.row.name}？`" @confirm="deleteDriver(scope.row)"><template #reference><el-button text type="danger">删除</el-button></template></el-popconfirm><span v-else class="status-text">{{ scope.row.builtin ? '不可删除' : '' }}</span></template></el-table-column>
        </el-table>
        <div class="pagination"><el-pagination :current-page="driversPage.page" :page-size="driverSize" :page-sizes="[10, 20, 50]" :total="driversPage.total" layout="total, sizes, prev, pager, next" @current-change="driverPage = $event" @size-change="driverSize = $event; driverPage = 1"/></div>
    </template>
    <el-dialog v-model="catalogOpen" title="选择数据库表并登记数据集" width="min(850px, 95vw)" @closed="inspectSequence++">
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/>
      <div class="catalog-controls"><el-select v-model="catalogSourceId" placeholder="选择数据源" @change="selectCatalogSource"><el-option v-for="source in items" :key="source.id" :label="source.name" :value="source.id"/></el-select><el-input v-model="schema" placeholder="Schema（可留空）"/><el-button :disabled="!selectedSource" @click="inspect(selectedSource)">查询表</el-button></div>
      <div class="catalog-controls"><el-input v-model="datasetName" placeholder="输入要登记的数据集名称"/><el-input v-model="tableSearch" clearable placeholder="搜索表名" @input="tablePage = 1"/></div>
      <el-table :data="tablesPage.rows" empty-text="选择数据源后查询数据库表" max-height="360"><el-table-column prop="schema" label="Schema"/><el-table-column prop="name" label="表名"/><el-table-column prop="type" label="类型"/><el-table-column label="操作" width="100"><template #default="scope"><el-button v-if="can('datasource.dataset.create')" text type="primary" :disabled="registering" @click="register(scope.row)">登记</el-button></template></el-table-column></el-table>
      <div class="pagination"><el-pagination :current-page="tablesPage.page" :page-size="tableSize" :page-sizes="[10, 20, 50]" :total="tablesPage.total" layout="total, sizes, prev, pager, next" @current-change="tablePage = $event" @size-change="tableSize = $event; tablePage = 1"/></div>
    </el-dialog>
    <el-dialog v-model="fieldsOpen" :title="`${fieldsDataset?.name || ''} 的字段`" width="min(650px, 95vw)"><el-table :data="fieldsDataset?.fields || []" max-height="450"><el-table-column prop="name" label="字段名"/><el-table-column prop="dataType" label="类型"/><el-table-column label="可为空"><template #default="scope">{{ scope.row.nullable ? '是' : '否' }}</template></el-table-column></el-table></el-dialog>
    <el-dialog v-model="policyOpen" title="数据集角色权限" width="min(600px, 95vw)"><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/><p>选择保存部门编号的数字字段；未授权字段不可查询。保存会替换该角色在此数据集上的原有规则。</p><p v-if="policyRules.length">已授权角色：<el-button v-for="rule in policyRules" :key="rule.roleId" text type="primary" @click="policyForm.roleId = rule.roleId; selectPolicyRole(rule.roleId)">{{ roleOptions.find(r => r.id === rule.roleId)?.name || `角色 ${rule.roleId}` }}</el-button></p><el-form label-position="top"><el-form-item label="部门字段"><el-select v-model="policyForm.departmentField"><el-option v-for="f in policyDataset?.fields || []" :key="f.name" :label="`${f.name} (${f.dataType})`" :value="f.name" /></el-select></el-form-item><el-form-item label="角色"><el-select v-if="roleOptions.length" v-model="policyForm.roleId" @change="selectPolicyRole"><el-option v-for="r in roleOptions" :key="r.id" :label="r.name" :value="r.id" /></el-select><el-input-number v-else v-model="policyForm.roleId" :min="1" placeholder="角色编号" @change="selectPolicyRole" /></el-form-item><el-form-item label="数据范围"><el-select v-model="policyForm.scope"><el-option label="本部门" value="OWN" /><el-option label="本部门及下级部门" value="OWN_AND_DESCENDANTS" /></el-select></el-form-item><el-form-item v-for="f in policyDataset?.fields || []" :key="f.name" :label="f.name"><el-radio-group v-model="policyForm.fields[f.name]"><el-radio-button value="NONE">不允许</el-radio-button><el-radio-button value="MASKED">脱敏</el-radio-button><el-radio-button value="FULL">明文</el-radio-button></el-radio-group></el-form-item></el-form><template #footer><el-popconfirm v-if="can('datasource.dataset.manage_policy') && policyRules.some(r => r.roleId === policyForm.roleId)" title="撤销该角色对数据集的授权？" @confirm="revokePolicy"><template #reference><el-button type="danger">撤销该角色授权</el-button></template></el-popconfirm><el-button @click="policyOpen = false">取消</el-button><el-button v-if="can('datasource.dataset.manage_policy')" type="primary" @click="savePolicy">保存范围</el-button></template></el-dialog>
    <el-dialog v-model="show" :title="editingId ? '编辑数据源' : '新建数据源'" width="min(560px, 95vw)" @closed="form.password = ''"><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/><el-alert v-if="editingId" title="修改连接会影响引用此数据源的任务和数据集；密码留空时保留原密码。" type="warning" :closable="false" show-icon/><el-form label-position="top" @submit.prevent="save"><el-form-item label="名称"><el-input v-model="form.name" /></el-form-item><el-form-item label="数据库类型"><el-select v-model="form.type" @change="selectType"><el-option v-for="t in typeOptions" :key="t.value" :label="t.label" :value="t.value" /></el-select></el-form-item><el-form-item label="大版本"><el-select v-model="form.profileId" @change="selectProfile"><el-option v-for="p in versionOptions" :key="p.id" :label="p.displayName" :value="p.id" /></el-select></el-form-item><el-form-item v-if="hasConnectType" label="连接方式"><el-radio-group v-model="form.connectType"><el-radio-button value="SID">SID</el-radio-button><el-radio-button value="SERVICE">服务名</el-radio-button></el-radio-group></el-form-item><el-form-item label="主机"><el-input v-model="form.host" /></el-form-item><el-form-item label="端口"><el-input-number v-model="form.port" :min="1" :max="65535" /></el-form-item><el-form-item :label="dbLabel"><el-input v-model="form.database" /></el-form-item><el-form-item label="用户名"><el-input v-model="form.username" /></el-form-item><el-form-item label="密码"><el-input v-model="form.password" type="password" show-password autocomplete="new-password" /></el-form-item><el-form-item label="JDBC 驱动"><el-select v-model="form.driverId" clearable placeholder="内置默认驱动"><el-option v-for="d in typeDrivers" :key="d.id" :label="`${d.name}${d.versionLabel ? ' ' + d.versionLabel : ''}${d.builtin ? '（内置）' : '（上传）'}`" :value="d.id" /></el-select></el-form-item><el-collapse class="advanced-params"><el-collapse-item :title="`高级参数（${advanced.length}）`" name="params"><div v-for="(row, index) in advanced" :key="index" class="param-row"><el-select v-model="row.key" placeholder="参数名" filterable><el-option v-for="spec in currentProfile?.extraParams || []" :key="spec.key" :label="spec.key" :value="spec.key"><span>{{ spec.key }}</span><span class="param-hint">{{ spec.label }}</span></el-option></el-select><el-input v-model="row.value" :placeholder="(currentProfile?.extraParams || []).find(s => s.key === row.key)?.hint || '参数值'" /><el-button text type="danger" @click="advanced.splice(index, 1)">移除</el-button></div><el-button text type="primary" :disabled="!currentProfile || !(currentProfile.extraParams || []).length" @click="advanced.push({ key: '', value: '' })">添加参数</el-button></el-collapse-item></el-collapse><el-button type="primary" native-type="submit" :loading="busy">保存</el-button></el-form></el-dialog>
    <el-dialog v-model="uploadOpen" title="上传 JDBC 驱动" width="min(520px, 95vw)"><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/><el-alert title="驱动 jar 会在隔离类加载器中运行，仅管理员可上传；上传前请确认来源可信。" type="warning" :closable="false" show-icon/><el-form label-position="top" @submit.prevent="submitUpload"><el-form-item label="驱动名称"><el-input v-model="uploadForm.name" placeholder="如 MySQL Connector/J 8.4" /></el-form-item><el-form-item label="数据库类型"><el-select v-model="uploadForm.dbType"><el-option v-for="t in typeOptions" :key="t.value" :label="t.label" :value="t.value" /></el-select></el-form-item><el-form-item label="驱动类"><el-input v-model="uploadForm.driverClass" placeholder="如 com.mysql.cj.jdbc.Driver" /></el-form-item><el-form-item label="版本标签（可留空自动探测）"><el-input v-model="uploadForm.versionLabel" placeholder="如 8.4.0" /></el-form-item><el-form-item label="jar 文件（≤50MB）"><el-upload drag accept=".jar" :auto-upload="false" :limit="1" :on-change="f => uploadForm.file = f.raw" :on-remove="() => uploadForm.file = null"><div class="upload-drag">将 jar 拖到此处，或点击选择</div></el-upload></el-form-item><el-button type="primary" native-type="submit" :loading="uploading">上传</el-button></el-form></el-dialog>
  </section>
</template>

<style scoped>
.toolbar,.catalog-controls{display:flex;gap:12px;align-items:center;margin:16px 0}.toolbar .el-input{max-width:320px}.catalog-controls > *{flex:1;min-width:0}.pagination{display:flex;justify-content:flex-end;margin:18px 0}.status-text{font-size:12px;color:#718581}.el-alert{margin:10px 0}.advanced-params{margin-bottom:14px}.param-row{display:flex;gap:8px;align-items:center;margin-bottom:8px}.param-row .el-select{width:46%}.param-row .el-input{flex:1}.param-hint{float:right;color:#8b98a8;font-size:12px}.upload-drag{padding:18px 10px;color:#66778c;font-size:13px}@media(max-width:700px){.toolbar,.catalog-controls{flex-wrap:wrap}.toolbar .el-input{max-width:none}.pagination{justify-content:flex-start;overflow:auto}.param-row{flex-wrap:wrap}.param-row .el-select{width:100%}}
</style>
