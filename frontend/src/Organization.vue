<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { authorizedRequest } from './auth.js'
import { allowedDepartmentParents, departmentName } from './organization.js'

const props = defineProps({ view: { type: String, default: 'departments' } })
const emit = defineEmits(['navigation-updated'])
const emptyData = () => ({ departments: [], roles: [], users: [], menus: [] })
const data = ref(emptyData())
const error = ref('')
const message = ref('')
const search = ref('')
const enabledFilter = ref('')
const departmentFilter = ref('')
const pageSize = 10
const pages = ref({ roles: 1, users: 1 })
const departmentDialog = ref(false)
const roleDialog = ref(false)
const userDialog = ref(false)
const menuDialog = ref(false)
const permissionDialog = ref(false)
const roleMenuDialog = ref(false)
const passwordDialog = ref(false)
const auditAction = ref('')
const auditEvents = ref([])
const auditMore = ref(false)
const auditLoadedAction = ref('')
const auditCursors = ref([null])
const auditPage = ref(0)
const auditBusy = ref(false)
const permissionRole = ref(null)
const selectedPermissions = ref([])
const menuRole = ref(null)
const roleMenuTree = ref(null)
const roleMenuSearch = ref('')
const roleMenuSelectedCount = ref(0)
const passwordTarget = ref(null)
const newPassword = ref('')
const departmentForm = ref({ id: null, name: '', parentId: null, sortOrder: 0, leader: '', phone: '', email: '', enabled: true })
const roleForm = ref({ id: null, name: '', roleKey: '', description: '', sortOrder: 0, enabled: true })
const userForm = ref({ id: null, username: '', nickname: '', email: '', phone: '', password: '', departmentId: null, enabled: true, roles: [] })
const menuForm = ref({ id: null, name: '', parentId: null, menuType: 'M', sortOrder: 0, routePath: '', component: '', permission: '', icon: '', visible: true, enabled: true })

const permissions = [
  ['organization.manage', '组织与权限管理'], ['datasource.manage', '数据源与资产管理'], ['dataset.read', '数据集读取'],
  ['workflow.validate', '工作流校验'], ['workflow.manage', '任务与工作流管理'], ['service.manage', '数据服务管理'], ['report.manage', '报表管理'],
]
const componentOptions = [
  ['overview', '平台概览'], ['assets', '数据资产'], ['tasks', '任务与工作流'], ['realtime', '实时同步'], ['operations', '运行运维'],
  ['services', '数据服务'], ['query', '数据查询'], ['reports', '数据报表'], ['system', '系统管理目录'],
  ['system/departments', '部门管理'], ['system/roles', '角色管理'], ['system/users', '用户管理'], ['system/menus', '菜单管理'], ['system/audit', '审计记录'],
]
const pageComponentOptions = componentOptions.filter(([value]) => value !== 'system')
const menuComponentOptions = computed(() => menuForm.value.menuType === 'D' ? componentOptions.filter(([value]) => value === 'system') : pageComponentOptions)

function matches(value, query) { return String(value ?? '').toLocaleLowerCase().includes(query) }
const filtered = computed(() => {
  const query = search.value.trim().toLocaleLowerCase()
  const enabled = enabledFilter.value
  const matchState = item => enabled === '' || String(item.enabled) === enabled
  return {
    departments: data.value.departments.filter(item => matchState(item) && (!query || [item.name, item.id, item.leader, item.phone].some(value => matches(value, query)))),
    roles: data.value.roles.filter(item => matchState(item) && (!query || [item.name, item.roleKey, item.id].some(value => matches(value, query)))),
    users: data.value.users.filter(item => matchState(item) && (departmentFilter.value === '' || item.departmentId === Number(departmentFilter.value)) && (!query || [item.username, item.nickname, item.email, item.phone, departmentName(data.value.departments, item.departmentId)].some(value => matches(value, query)))),
    menus: data.value.menus.filter(item => matchState(item) && (!query || [item.name, item.routePath, item.permission, item.component].some(value => matches(value, query)))),
  }
})
function page(items, name) {
  const totalPages = Math.max(1, Math.ceil(items.length / pageSize))
  const index = Math.min(pages.value[name], totalPages)
  if (pages.value[name] !== index) pages.value[name] = index
  return items.slice((index - 1) * pageSize, index * pageSize)
}
watch([search, enabledFilter, departmentFilter], () => { pages.value = { roles: 1, users: 1 } })
watch(filtered, result => {
  for (const name of ['roles', 'users']) pages.value[name] = Math.min(pages.value[name], Math.max(1, Math.ceil(result[name].length / pageSize)))
})

function nested(items, parentId = null) {
  return items.filter(item => item.parentId === parentId).map(item => ({ ...item, children: nested(items, item.id) }))
}
const departmentRows = computed(() => search.value.trim() || enabledFilter.value !== '' ? filtered.value.departments : nested(data.value.departments))
const menuRows = computed(() => search.value.trim() || enabledFilter.value !== '' ? filtered.value.menus : nested(data.value.menus))
const menuOptions = computed(() => nested(data.value.menus.filter(item => item.menuType !== 'F' && item.id !== menuForm.value.id)))

async function load() {
  try {
    const response = await fetch('/api/organization', { credentials: 'same-origin' })
    if (!response.ok) throw new Error(response.status === 403 ? '当前账号没有组织管理权限' : '无法读取系统管理数据')
    data.value = await response.json()
    error.value = ''
  } catch (e) { error.value = e.message }
}
async function mutate(path, body, success, method = 'POST') {
  try {
    error.value = ''
    const options = { method }
    if (body !== undefined) { options.headers = { 'Content-Type': 'application/json' }; options.body = JSON.stringify(body) }
    const response = await authorizedRequest(`/api/organization${path}`, options)
    if (!response.ok) {
      const payload = await response.json().catch(() => null)
      throw new Error(payload?.error || (response.status === 403 ? '当前账号没有组织管理权限' : '保存失败，请检查输入'))
    }
    message.value = success
    await load()
    emit('navigation-updated')
    return true
  } catch (e) { error.value = e.message; return false }
}

function openDepartment(item = null) {
  departmentForm.value = item ? { id: item.id, name: item.name, parentId: item.parentId, sortOrder: item.sortOrder, leader: item.leader || '', phone: item.phone || '', email: item.email || '', enabled: item.enabled } : { id: null, name: '', parentId: null, sortOrder: 0, leader: '', phone: '', email: '', enabled: true }
  departmentDialog.value = true
}
async function saveDepartment() {
  const form = departmentForm.value
  const { id, ...body } = form
  const ok = await mutate(id ? `/departments/${id}` : '/departments', body, id ? '部门信息已更新' : '部门已创建')
  if (ok) departmentDialog.value = false
}
function openRole(item = null) {
  roleForm.value = item ? { id: item.id, name: item.name, roleKey: item.roleKey, description: item.description || '', sortOrder: item.sortOrder, enabled: item.enabled } : { id: null, name: '', roleKey: '', description: '', sortOrder: 0, enabled: true }
  roleDialog.value = true
}
async function saveRole() {
  const form = roleForm.value
  const { id, ...body } = form
  const ok = await mutate(id ? `/roles/${id}` : '/roles', body, id ? '角色信息已更新' : '角色已创建')
  if (ok) roleDialog.value = false
}
async function setMenuVisible(item, visible) {
  const body = { name: item.name, parentId: item.parentId, menuType: item.menuType, sortOrder: item.sortOrder, routePath: item.routePath, component: item.component, permission: item.permission, icon: item.icon, visible, enabled: item.enabled }
  await mutate(`/menus/${item.id}`, body, visible ? '菜单已设为显示' : '菜单已设为隐藏')
}
function openPermissions(item) { permissionRole.value = item; selectedPermissions.value = [...item.permissions]; permissionDialog.value = true }
async function savePermissions() {
  if (await mutate(`/roles/${permissionRole.value.id}/permissions`, { permissions: selectedPermissions.value }, '角色权限已更新')) permissionDialog.value = false
}
async function openRoleMenus(item) {
  menuRole.value = item
  roleMenuSearch.value = ''
  roleMenuDialog.value = true
  await nextTick()
  roleMenuTree.value?.setCheckedKeys(item.menuIds || [], false)
  roleMenuTree.value?.filter('')
  setRoleMenuExpanded(true)
  updateRoleMenuSelection()
}
async function saveRoleMenus() {
  const menus = roleMenuTree.value?.getCheckedKeys(false) || []
  if (await mutate(`/roles/${menuRole.value.id}/menus`, { menus }, '角色菜单已更新')) roleMenuDialog.value = false
}
function updateRoleMenuSelection(_node, state) {
  roleMenuSelectedCount.value = state?.checkedKeys?.length ?? roleMenuTree.value?.getCheckedKeys(false).length ?? 0
}
function filterRoleMenuNode(value, item) {
  const query = String(value || '').trim().toLocaleLowerCase()
  return !query || String(item.label || item.name || '').toLocaleLowerCase().includes(query)
}
function setRoleMenuExpanded(expanded) {
  const visit = items => items.forEach(item => {
    if (item.children?.length) {
      const node = roleMenuTree.value?.getNode(item.id)
      if (expanded) node?.expand()
      else node?.collapse()
      visit(item.children)
    }
  })
  visit(roleMenuRows.value)
}
function toggleAllRoleMenus() {
  const keys = roleMenuAllSelected.value ? [] : collectRoleMenuKeys(roleMenuRows.value)
  roleMenuTree.value?.setCheckedKeys(keys, false)
  updateRoleMenuSelection()
}
function openUser(item = null) {
  userForm.value = item ? { id: item.id, username: item.username, nickname: item.nickname || '', email: item.email || '', phone: item.phone || '', password: '', departmentId: item.departmentId, enabled: item.enabled, roles: [...item.roles] } : { id: null, username: '', nickname: '', email: '', phone: '', password: '', departmentId: data.value.departments[0]?.id ?? null, enabled: true, roles: [] }
  userDialog.value = true
}
async function saveUser() {
  const form = userForm.value
  const { id, ...body } = form
  const ok = await mutate(id ? `/users/${id}` : '/users', body, id ? '用户信息已更新' : '用户已创建')
  if (ok) userDialog.value = false
}
function openMenu(item = null) {
  menuForm.value = item ? { id: item.id, name: item.name, parentId: item.parentId, menuType: item.menuType, sortOrder: item.sortOrder, routePath: item.routePath, component: item.component, permission: item.permission, icon: item.icon, visible: item.visible, enabled: item.enabled } : { id: null, name: '', parentId: null, menuType: 'M', sortOrder: 0, routePath: '', component: 'system/users', permission: '', icon: '', visible: true, enabled: true }
  menuDialog.value = true
}
watch(() => menuForm.value.menuType, type => {
  if (type === 'D') { menuForm.value.routePath = ''; menuForm.value.component = 'system' }
  if (type === 'F') { menuForm.value.routePath = ''; menuForm.value.component = '' }
  if (type === 'M' && (!menuForm.value.component || menuForm.value.component === 'system')) menuForm.value.component = 'system/users'
})
async function saveMenu() {
  const form = menuForm.value
  const { id, ...body } = form
  const ok = await mutate(id ? `/menus/${id}` : '/menus', body, id ? '菜单信息已更新' : '菜单已创建')
  if (ok) menuDialog.value = false
}

function roleLabels(ids) { return ids.map(id => data.value.roles.find(role => role.id === id)?.name ?? `角色 ${id}`).join('、') || '暂无角色' }
function permissionLabels(ids) { return ids.map(id => permissions.find(([value]) => value === id)?.[1] ?? id).join('、') || '暂无权限' }
function menuTypeLabel(type) { return ({ D: '目录', M: '菜单', F: '按钮' })[type] || type }
function menuName(id) { return data.value.menus.find(item => item.id === id)?.name ?? `菜单 ${id}` }
function auditMenuTree(items, parentId = null) { return items.filter(item => item.parentId === parentId).map(item => ({ ...item, label: item.name, children: auditMenuTree(items, item.id) })) }
function collectRoleMenuKeys(items) { return items.flatMap(item => [item.id, ...collectRoleMenuKeys(item.children || [])]) }
const roleMenuRows = computed(() => auditMenuTree(data.value.menus))
const roleMenuTotalCount = computed(() => collectRoleMenuKeys(roleMenuRows.value).length)
const roleMenuAllSelected = computed(() => roleMenuTotalCount.value > 0 && roleMenuSelectedCount.value >= roleMenuTotalCount.value)
const roleMenuSearchMatches = computed(() => {
  const query = roleMenuSearch.value.trim().toLocaleLowerCase()
  return !query || collectRoleMenuKeys(roleMenuRows.value).some(key => {
    const item = data.value.menus.find(menu => menu.id === key)
    return String(item?.name || '').toLocaleLowerCase().includes(query)
  })
})
watch(roleMenuSearch, value => roleMenuTree.value?.filter(value))

async function confirmDelete(path, label, success) {
  try {
    await ElMessageBox.confirm(`确定删除${label}？`, '删除确认', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
    await mutate(path, undefined, success, 'DELETE')
  } catch { /* Cancelled by the operator. */ }
}
function setPassword(item) { passwordTarget.value = item; newPassword.value = ''; passwordDialog.value = true }
async function resetPassword() {
  if (await mutate(`/users/${passwordTarget.value.id}/password`, { password: newPassword.value }, '密码已重置，原会话将失效')) passwordDialog.value = false
}

async function loadAudit(index = 0, reset = false) {
  if (auditBusy.value) return
  auditBusy.value = true
  try {
    const action = auditAction.value.trim()
    if (reset || action !== auditLoadedAction.value) { index = 0; auditCursors.value = [null] }
    const query = new URLSearchParams({ limit: '10' })
    if (action) query.set('action', action)
    if (auditCursors.value[index] != null) query.set('beforeId', auditCursors.value[index])
    const response = await fetch(`/api/organization/audit?${query}`, { credentials: 'same-origin' })
    if (!response.ok) throw new Error('读取审计记录失败，请检查权限')
    const result = await response.json()
    auditEvents.value = result; auditPage.value = index; auditMore.value = result.length === 10; auditLoadedAction.value = action; error.value = ''
  } catch (e) { error.value = e.message } finally { auditBusy.value = false }
}
function nextAudit() {
  if (!auditMore.value || !auditEvents.value.length) return
  const nextPage = auditPage.value + 1
  auditCursors.value = [...auditCursors.value.slice(0, nextPage), auditEvents.value.at(-1).id]
  loadAudit(nextPage)
}
watch(() => props.view, view => { search.value = ''; enabledFilter.value = ''; departmentFilter.value = ''; if (view === 'audit') loadAudit(0, true) }, { immediate: true })
onMounted(load)
</script>

<template>
  <section class="organization-page">
    <el-alert v-if="error" class="notice" :title="error" type="error" :closable="true" show-icon @close="error = ''" />
    <el-alert v-if="message" class="notice" :title="message" type="success" :closable="true" show-icon @close="message = ''" />

    <template v-if="props.view === 'departments'">
      <div class="toolbar">
        <el-input v-model="search" clearable placeholder="搜索部门名称、负责人或编号" />
        <el-select v-model="enabledFilter" clearable placeholder="部门状态"><el-option label="启用" value="true" /><el-option label="停用" value="false" /></el-select>
        <el-button type="primary" @click="openDepartment()">新建部门</el-button><el-button @click="load">刷新</el-button>
      </div>
      <el-table :data="departmentRows" row-key="id" :tree-props="{ children: 'children' }" default-expand-all>
        <el-table-column prop="name" label="部门名称" min-width="190" />
        <el-table-column prop="id" label="部门编号" min-width="100" />
        <el-table-column label="上级部门" min-width="150"><template #default="scope">{{ departmentName(data.departments, scope.row.parentId) }}</template></el-table-column>
        <el-table-column prop="leader" label="负责人" min-width="130"><template #default="scope">{{ scope.row.leader || '—' }}</template></el-table-column>
        <el-table-column prop="phone" label="联系电话" min-width="140"><template #default="scope">{{ scope.row.phone || '—' }}</template></el-table-column>
        <el-table-column prop="sortOrder" label="排序" width="90" />
        <el-table-column label="状态" width="100"><template #default="scope"><el-tag :type="scope.row.enabled ? 'success' : 'info'" effect="light">{{ scope.row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="190" fixed="right"><template #default="scope"><el-button link type="primary" @click="openDepartment(scope.row)">修改</el-button><el-button link type="danger" @click="confirmDelete(`/departments/${scope.row.id}`, `部门「${scope.row.name}」`, '部门已删除')">删除</el-button></template></el-table-column>
      </el-table>
    </template>

    <template v-else-if="props.view === 'roles'">
      <div class="toolbar">
        <el-input v-model="search" clearable placeholder="搜索角色名称、权限字符或编号" />
        <el-select v-model="enabledFilter" clearable placeholder="角色状态"><el-option label="启用" value="true" /><el-option label="停用" value="false" /></el-select>
        <el-button type="primary" @click="openRole()">新建角色</el-button><el-button @click="load">刷新</el-button>
      </div>
      <el-table :data="page(filtered.roles, 'roles')" row-key="id">
        <el-table-column prop="name" label="角色名称" min-width="150" />
        <el-table-column prop="roleKey" label="权限字符" min-width="170" />
        <el-table-column prop="sortOrder" label="排序" width="85" />
        <el-table-column label="状态" width="95"><template #default="scope"><el-tag :type="scope.row.enabled ? 'success' : 'info'" effect="light">{{ scope.row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column label="功能权限" min-width="230"><template #default="scope">{{ permissionLabels(scope.row.permissions) }}</template></el-table-column>
        <el-table-column label="菜单授权" min-width="180"><template #default="scope">{{ scope.row.menuIds.length }} 项菜单</template></el-table-column>
        <el-table-column label="操作" min-width="280" fixed="right"><template #default="scope"><el-button link type="primary" @click="openRole(scope.row)">修改</el-button><el-button link type="primary" @click="openPermissions(scope.row)">功能权限</el-button><el-button link type="primary" @click="openRoleMenus(scope.row)">菜单授权</el-button><el-button link type="danger" @click="confirmDelete(`/roles/${scope.row.id}`, `角色「${scope.row.name}」`, '角色已删除')">删除</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="pages.roles" class="pagination" layout="total, prev, pager, next" :page-size="pageSize" :total="filtered.roles.length" />
    </template>

    <template v-else-if="props.view === 'users'">
      <div class="toolbar">
        <el-input v-model="search" clearable placeholder="搜索账号、姓名、部门或手机号" />
        <el-select v-model="departmentFilter" clearable placeholder="所属部门"><el-option v-for="item in data.departments" :key="item.id" :label="item.name" :value="item.id" /></el-select>
        <el-select v-model="enabledFilter" clearable placeholder="用户状态"><el-option label="启用" value="true" /><el-option label="停用" value="false" /></el-select>
        <el-button type="primary" @click="openUser()">新建用户</el-button><el-button @click="load">刷新</el-button>
      </div>
      <el-table :data="page(filtered.users, 'users')" row-key="id">
        <el-table-column prop="username" label="用户账号" min-width="140" />
        <el-table-column prop="nickname" label="用户姓名" min-width="140" />
        <el-table-column label="所属部门" min-width="150"><template #default="scope">{{ departmentName(data.departments, scope.row.departmentId) }}</template></el-table-column>
        <el-table-column prop="phone" label="手机号码" min-width="140"><template #default="scope">{{ scope.row.phone || '—' }}</template></el-table-column>
        <el-table-column label="角色" min-width="180"><template #default="scope">{{ roleLabels(scope.row.roles) }}</template></el-table-column>
        <el-table-column label="状态" width="95"><template #default="scope"><el-tag :type="scope.row.enabled ? 'success' : 'info'" effect="light">{{ scope.row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" min-width="250" fixed="right"><template #default="scope"><el-button link type="primary" @click="openUser(scope.row)">修改</el-button><el-button link @click="setPassword(scope.row)">重置密码</el-button><el-button link type="danger" @click="confirmDelete(`/users/${scope.row.id}`, `用户「${scope.row.username}」`, '用户已删除')">删除</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="pages.users" class="pagination" layout="total, prev, pager, next" :page-size="pageSize" :total="filtered.users.length" />
    </template>

    <template v-else-if="props.view === 'menus'">
      <div class="toolbar">
        <el-input v-model="search" clearable placeholder="搜索菜单名称、路由或权限标识" />
        <el-select v-model="enabledFilter" clearable placeholder="菜单状态"><el-option label="启用" value="true" /><el-option label="停用" value="false" /></el-select>
        <el-button type="primary" @click="openMenu()">新增菜单</el-button><el-button @click="load">刷新</el-button>
      </div>
      <el-table :data="menuRows" row-key="id" :tree-props="{ children: 'children' }" default-expand-all>
        <el-table-column prop="name" label="菜单名称" min-width="180" />
        <el-table-column label="类型" width="100"><template #default="scope"><el-tag effect="plain">{{ menuTypeLabel(scope.row.menuType) }}</el-tag></template></el-table-column>
        <el-table-column prop="icon" label="图标" width="110"><template #default="scope">{{ scope.row.icon || '—' }}</template></el-table-column>
        <el-table-column prop="routePath" label="路由地址" min-width="190"><template #default="scope">{{ scope.row.routePath || '—' }}</template></el-table-column>
        <el-table-column prop="permission" label="权限标识" min-width="180"><template #default="scope">{{ scope.row.permission || '—' }}</template></el-table-column>
        <el-table-column prop="sortOrder" label="排序" width="85" />
        <el-table-column label="显示" width="100"><template #default="scope"><el-switch :model-value="scope.row.visible" :disabled="scope.row.id === 9 || scope.row.id === 94" @change="setMenuVisible(scope.row, $event)" /></template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="scope"><el-tag :type="scope.row.enabled ? 'success' : 'info'" effect="light">{{ scope.row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="150" fixed="right"><template #default="scope"><el-button link type="primary" @click="openMenu(scope.row)">修改</el-button><el-button link type="danger" :disabled="scope.row.id === 94" @click="confirmDelete(`/menus/${scope.row.id}`, `菜单「${scope.row.name}」`, '菜单已删除')">删除</el-button></template></el-table-column>
      </el-table>
    </template>

    <template v-else-if="props.view === 'audit'">
      <div class="pane">
        <div class="toolbar audit-toolbar"><el-input v-model="auditAction" clearable placeholder="操作类型（可留空）" @keyup.enter="loadAudit(0, true)" /><el-button type="primary" @click="loadAudit(0, true)">查询</el-button><el-button @click="loadAudit(0, true)">刷新</el-button></div>
        <el-alert title="登录失败等没有关联账号的记录显示为系统或未知账号。" type="info" :closable="false" show-icon />
        <el-table :data="auditEvents" class="audit-table">
          <el-table-column prop="occurredAt" label="时间" min-width="180" />
          <el-table-column label="操作者" min-width="170"><template #default="scope">{{ scope.row.username || '系统或未知账号' }}</template></el-table-column>
          <el-table-column prop="action" label="操作" min-width="170" />
          <el-table-column prop="objectType" label="对象类型" min-width="120" />
          <el-table-column prop="objectId" label="对象编号" min-width="120" />
          <el-table-column label="结果" min-width="110"><template #default="scope"><el-tag :type="scope.row.result === 'SUCCESS' ? 'success' : 'danger'" effect="light">{{ scope.row.result }}</el-tag></template></el-table-column>
        </el-table>
        <div class="pagination"><el-button :disabled="auditPage === 0 || auditBusy" @click="loadAudit(auditPage - 1)">上一页</el-button><span>第 {{ auditPage + 1 }} 页</span><el-button :disabled="!auditMore || auditBusy" @click="nextAudit">下一页</el-button></div>
      </div>
    </template>

    <el-dialog v-model="departmentDialog" :title="departmentForm.id ? '修改部门' : '新增部门'" width="min(620px, 95vw)">
      <el-form :model="departmentForm" label-position="top" class="form-grid" @submit.prevent="saveDepartment">
        <el-form-item label="部门名称" required><el-input v-model="departmentForm.name" maxlength="160" show-word-limit /></el-form-item>
        <el-form-item label="上级部门"><el-select v-model="departmentForm.parentId" clearable placeholder="无上级部门"><el-option v-for="item in allowedDepartmentParents(data.departments, departmentForm.id)" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="负责人"><el-input v-model="departmentForm.leader" maxlength="160" /></el-form-item>
        <el-form-item label="联系电话"><el-input v-model="departmentForm.phone" maxlength="48" /></el-form-item>
        <el-form-item label="邮箱"><el-input v-model="departmentForm.email" maxlength="160" /></el-form-item>
        <el-form-item label="显示排序"><el-input-number v-model="departmentForm.sortOrder" :min="0" :max="100000" /></el-form-item>
        <el-form-item label="状态"><el-switch v-model="departmentForm.enabled" active-text="启用" inactive-text="停用" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="departmentDialog = false">取消</el-button><el-button type="primary" @click="saveDepartment">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="roleDialog" :title="roleForm.id ? '修改角色' : '新增角色'" width="min(620px, 95vw)">
      <el-form :model="roleForm" label-position="top" class="form-grid">
        <el-form-item label="角色名称" required><el-input v-model="roleForm.name" maxlength="160" /></el-form-item>
        <el-form-item label="权限字符" required><el-input v-model="roleForm.roleKey" maxlength="160" placeholder="例如 data_operator" /></el-form-item>
        <el-form-item label="显示排序"><el-input-number v-model="roleForm.sortOrder" :min="0" :max="100000" /></el-form-item>
        <el-form-item label="状态"><el-switch v-model="roleForm.enabled" active-text="启用" inactive-text="停用" /></el-form-item>
        <el-form-item label="备注" class="full-row"><el-input v-model="roleForm.description" type="textarea" :rows="3" maxlength="500" show-word-limit /></el-form-item>
      </el-form>
      <template #footer><el-button @click="roleDialog = false">取消</el-button><el-button type="primary" @click="saveRole">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="permissionDialog" title="配置功能权限" width="min(560px, 95vw)">
      <p class="dialog-hint">为角色「{{ permissionRole?.name }}」分配后端功能权限。</p>
      <el-checkbox-group v-model="selectedPermissions" class="check-grid"><el-checkbox v-for="[value, label] in permissions" :key="value" :value="value">{{ label }}</el-checkbox></el-checkbox-group>
      <template #footer><el-button @click="permissionDialog = false">取消</el-button><el-button type="primary" @click="savePermissions">保存权限</el-button></template>
    </el-dialog>

    <el-dialog v-model="roleMenuDialog" class="role-menu-dialog" width="min(820px, 95vw)" top="4vh" :close-on-click-modal="false">
      <template #header="{ titleId, titleClass }">
        <div class="role-menu-header">
          <div class="role-menu-title-row">
            <span class="role-menu-mark" aria-hidden="true">权</span>
            <h2 :id="titleId" :class="titleClass">配置角色菜单</h2>
            <span class="role-menu-role">{{ menuRole?.name }}</span>
          </div>
          <p>勾选角色可见的菜单；页面访问还需在“功能权限”中授予对应权限。</p>
          <div class="role-menu-summary"><span class="role-menu-dot"></span><span>菜单访问范围</span><span class="role-menu-count"><strong>{{ roleMenuSelectedCount }}</strong> 项已选择</span></div>
        </div>
      </template>
      <div class="role-menu-toolbar">
        <el-input v-model="roleMenuSearch" class="role-menu-search" clearable placeholder="搜索菜单名称" aria-label="搜索菜单名称">
          <template #prefix><svg viewBox="0 0 20 20" aria-hidden="true"><circle cx="8.5" cy="8.5" r="5.7"/><path d="m13 13 4 4"/></svg></template>
        </el-input>
        <div class="role-menu-tools">
          <el-button @click="setRoleMenuExpanded(true)">展开全部</el-button>
          <el-button @click="setRoleMenuExpanded(false)">收起全部</el-button>
          <el-button type="primary" plain @click="toggleAllRoleMenus">{{ roleMenuAllSelected ? '清空选择' : '全选' }}</el-button>
        </div>
      </div>
      <div class="role-menu-tree-area">
        <div class="role-menu-tree-card">
          <div class="role-menu-tree-caption"><span>菜单目录</span><span>勾选后角色即可在侧边栏看到该菜单</span></div>
          <div class="role-menu-tree-scroll">
            <div v-if="!roleMenuSearchMatches" class="role-menu-empty">没有匹配的菜单</div>
            <el-tree v-show="roleMenuSearchMatches" ref="roleMenuTree" :data="roleMenuRows" node-key="id" show-checkbox default-expand-all :expand-on-click-node="false" :filter-node-method="filterRoleMenuNode" :props="{ label: 'label', children: 'children' }" @check="updateRoleMenuSelection">
              <template #default="{ data: menuItem, node }">
                <div class="role-menu-node">
                  <span class="role-menu-node-icon" aria-hidden="true">
                    <svg v-if="menuItem.menuType === 'D'" viewBox="0 0 20 20"><path d="M2.5 5.5h6l1.5 2h7.5v8.8H2.5z"/><path d="M2.5 5.5V4h6l1.5 1.5"/></svg>
                    <svg v-else-if="menuItem.menuType === 'F'" viewBox="0 0 20 20"><circle cx="7" cy="9" r="4"/><path d="m10 12 6 6m-2-2 2-2m-4 0 2-2"/></svg>
                    <svg v-else viewBox="0 0 20 20"><rect x="3" y="3" width="14" height="14" rx="2"/><path d="M7 7h6M7 10h6M7 13h3"/></svg>
                  </span>
                  <span class="role-menu-node-label">{{ node.label }}</span>
                  <span v-if="menuItem.children?.length" class="role-menu-child-count">{{ menuItem.children.length }} 个子菜单</span>
                </div>
              </template>
            </el-tree>
          </div>
          <div class="role-menu-tree-note"><span class="role-menu-info" aria-hidden="true">i</span><span>菜单可见性与后端功能权限分别控制</span></div>
        </div>
      </div>
      <template #footer>
        <div class="role-menu-footer"><span class="role-menu-footer-note"><span class="role-menu-dot"></span>当前已选择 <strong>{{ roleMenuSelectedCount }}</strong> 个菜单项</span><span class="role-menu-footer-actions"><el-button @click="roleMenuDialog = false">取消</el-button><el-button type="primary" @click="saveRoleMenus">保存菜单</el-button></span></div>
      </template>
    </el-dialog>

    <el-dialog v-model="userDialog" :title="userForm.id ? '修改用户' : '新增用户'" width="min(680px, 95vw)" @closed="userForm.password = ''">
      <el-form :model="userForm" label-position="top" class="form-grid">
        <el-form-item label="用户账号" required><el-input v-model="userForm.username" autocomplete="off" maxlength="160" /></el-form-item>
        <el-form-item label="用户姓名"><el-input v-model="userForm.nickname" maxlength="160" /></el-form-item>
        <el-form-item v-if="!userForm.id" label="初始密码" required><el-input v-model="userForm.password" type="password" show-password autocomplete="new-password" /></el-form-item>
        <el-form-item label="所属部门" required><el-select v-model="userForm.departmentId" placeholder="请选择部门"><el-option v-for="item in data.departments" :key="item.id" :label="item.name" :value="item.id" :disabled="!item.enabled && item.id !== userForm.departmentId" /></el-select></el-form-item>
        <el-form-item label="手机号码"><el-input v-model="userForm.phone" maxlength="48" /></el-form-item>
        <el-form-item label="邮箱"><el-input v-model="userForm.email" maxlength="160" /></el-form-item>
        <el-form-item label="角色"><el-select v-model="userForm.roles" multiple collapse-tags collapse-tags-tooltip placeholder="选择角色" :disabled="userForm.id === 1"><el-option v-for="item in data.roles" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="状态"><el-switch v-model="userForm.enabled" active-text="启用" inactive-text="停用" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="userDialog = false">取消</el-button><el-button type="primary" @click="saveUser">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="menuDialog" :title="menuForm.id ? '修改菜单' : '新增菜单'" width="min(680px, 95vw)">
      <el-form :model="menuForm" label-position="top" class="form-grid">
        <el-form-item label="菜单名称" required><el-input v-model="menuForm.name" maxlength="160" /></el-form-item>
        <el-form-item label="上级菜单"><el-tree-select v-model="menuForm.parentId" :data="menuOptions" node-key="id" clearable check-strictly default-expand-all :disabled="menuForm.component === 'system' || menuForm.component === 'system/menus'" :props="{ label: 'name', children: 'children', value: 'id' }" placeholder="顶级菜单" /></el-form-item>
        <el-form-item label="菜单类型" required><el-select v-model="menuForm.menuType" :disabled="menuForm.id === 9 || menuForm.id === 94"><el-option label="目录" value="D" /><el-option label="菜单" value="M" /><el-option label="按钮" value="F" /></el-select></el-form-item>
        <el-form-item label="显示排序"><el-input-number v-model="menuForm.sortOrder" :min="0" :max="100000" /></el-form-item>
        <el-form-item v-if="menuForm.menuType === 'M'" label="路由地址" required><el-input v-model="menuForm.routePath" maxlength="255" placeholder="例如 /system/users" /></el-form-item>
        <el-form-item v-if="menuForm.menuType !== 'F'" label="组件标识" :required="menuForm.menuType === 'M'"><el-select v-model="menuForm.component" filterable :disabled="menuForm.id === 9 || menuForm.id === 94"><el-option v-for="[value, label] in menuComponentOptions" :key="value" :label="label" :value="value" /></el-select></el-form-item>
        <el-form-item :label="menuForm.menuType === 'D' ? '菜单权限（可选）' : '权限标识'" :required="menuForm.menuType !== 'D'"><el-select v-model="menuForm.permission" clearable filterable :disabled="menuForm.id === 9 || menuForm.id === 94"><el-option v-for="[value, label] in permissions" :key="value" :label="`${label} · ${value}`" :value="value" /></el-select></el-form-item>
        <el-form-item v-if="menuForm.menuType !== 'F'" label="菜单图标"><el-input v-model="menuForm.icon" maxlength="80" placeholder="如 users、menu、database" /></el-form-item>
        <el-form-item label="显示状态"><el-switch v-model="menuForm.visible" active-text="显示" inactive-text="隐藏" /></el-form-item>
        <el-form-item label="菜单状态"><el-switch v-model="menuForm.enabled" active-text="启用" inactive-text="停用" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="menuDialog = false">取消</el-button><el-button type="primary" @click="saveMenu">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="passwordDialog" title="重置密码" width="min(460px, 95vw)" @closed="newPassword = ''">
      <p class="dialog-hint">为 {{ passwordTarget?.username }} 设置新密码，原有会话将失效。</p>
      <el-input v-model="newPassword" type="password" show-password autocomplete="new-password" placeholder="请输入新密码" />
      <template #footer><el-button @click="passwordDialog = false">取消</el-button><el-button type="primary" @click="resetPassword">重置密码</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.organization-page{min-width:0;color:#25364a}.notice{margin-bottom:12px}.toolbar{display:flex;align-items:center;gap:10px;margin:0 0 10px;padding:10px 12px;background:#fff;border:1px solid #e5eaf0;border-radius:4px}.toolbar .el-input{width:min(340px,100%)}.toolbar .el-select{width:150px}.el-table{width:100%;background:#fff}.pagination{display:flex;justify-content:flex-end;margin:14px 0 0}.pane{padding:12px;background:#fff;border:1px solid #e5eaf0;border-radius:4px}.audit-toolbar{margin:0 0 10px;padding:0;border:0}.audit-table{margin-top:10px}.menu-state-switch{margin-left:8px}.form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 18px}.form-grid .el-form-item{margin-bottom:16px}.form-grid .full-row{grid-column:1/-1}.form-grid :deep(.el-select),.form-grid :deep(.el-tree-select){width:100%}.dialog-hint{margin:0 0 16px;color:#66788a}.check-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px 12px}.pagination span{align-self:center;color:#556579}
@media(min-width:1440px){.toolbar{gap:clamp(10px,.65vw,16px)}.toolbar .el-input{width:clamp(340px,23vw,480px)}.pane{padding:clamp(12px,.8vw,20px)}}
@media(max-width:720px){.toolbar{flex-wrap:wrap}.toolbar .el-input{width:100%}.toolbar .el-select{flex:1;min-width:135px}.form-grid{grid-template-columns:1fr}.form-grid .full-row{grid-column:auto}.check-grid{grid-template-columns:1fr}}
:global(.role-menu-dialog.el-dialog){display:flex;flex-direction:column;max-height:calc(100dvh - 48px);overflow:hidden;border:1px solid #dde6f0;border-radius:12px;box-shadow:0 22px 64px rgba(25,44,72,.2)}
:global(.role-menu-dialog .el-dialog__header){flex:none;margin:0;padding:0;border-bottom:1px solid #edf1f6}
:global(.role-menu-dialog .el-dialog__headerbtn){top:19px;right:20px;width:32px;height:32px;border-radius:7px;color:#8291a4}
:global(.role-menu-dialog .el-dialog__headerbtn:hover){background:#f1f5fa;color:#3d536e}
:global(.role-menu-dialog .el-dialog__body){display:flex;flex:1 1 auto;flex-direction:column;min-height:0;overflow:hidden;padding:0}
:global(.role-menu-dialog .el-dialog__footer){flex:none;margin:0;padding:0;border-top:1px solid #e8edf3}
.role-menu-header{padding:20px 26px 15px}.role-menu-title-row{display:flex;align-items:center;gap:11px;padding-right:34px}.role-menu-mark{width:34px;height:34px;display:grid;place-items:center;flex:none;border-radius:9px;background:#edf5ff;color:#347ccf;font-size:15px;font-weight:700}.role-menu-header h2{margin:0;color:#26384f;font-size:19px;font-weight:650;letter-spacing:.01em}.role-menu-role{max-width:190px;overflow:hidden;padding:5px 9px;border:1px solid #d8e7fa;border-radius:6px;background:#f5f9ff;color:#3975bd;font-size:12px;font-weight:600;text-overflow:ellipsis;white-space:nowrap}.role-menu-header>p{margin:9px 0 0 45px;color:#718198;font-size:12px;line-height:1.5}.role-menu-summary{display:flex;align-items:center;gap:8px;margin:13px 0 0 45px;color:#77879c;font-size:12px}.role-menu-dot{width:6px;height:6px;flex:none;border-radius:50%;background:#438ce8}.role-menu-count{display:inline-flex;align-items:center;gap:5px;margin-left:auto;padding:5px 9px;border-radius:6px;background:#eef5ff;color:#347bc9;font-weight:600}.role-menu-count strong{font-size:13px}
.role-menu-toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:12px 26px;border-bottom:1px solid #edf1f6;background:#fbfcfe}.role-menu-search{width:min(360px,45%)}.role-menu-search :deep(.el-input__wrapper){min-height:36px;border:1px solid #dfe6ef;border-radius:7px;background:#fff;box-shadow:none}.role-menu-search :deep(.el-input__wrapper.is-focus){border-color:#86b6ef;box-shadow:0 0 0 2px rgba(67,140,232,.1)}.role-menu-search :deep(.el-input__prefix-inner svg){width:15px;height:15px;fill:none;stroke:#8494a8;stroke-width:1.7}.role-menu-tools{display:flex;align-items:center;gap:7px}.role-menu-tools :deep(.el-button){height:34px;margin:0;padding:0 10px;border-radius:6px;font-size:12px}
.role-menu-tree-area{display:flex;flex:1 1 auto;min-height:0;padding:14px 26px 16px;background:#f7f9fc}.role-menu-tree-card{display:flex;flex:1;flex-direction:column;min-width:0;min-height:0;overflow:hidden;border:1px solid #e3eaf2;border-radius:8px;background:#fff}.role-menu-tree-caption{display:flex;flex:none;align-items:center;justify-content:space-between;gap:12px;height:36px;padding:0 14px;border-bottom:1px solid #edf1f6;background:#fbfcfe;color:#718198;font-size:11px}.role-menu-tree-caption span:first-child{font-weight:650;color:#4f627b}.role-menu-tree-caption span:last-child{color:#98a5b5}.role-menu-tree-scroll{position:relative;flex:1;min-height:0;overflow:auto;padding:8px 8px 10px}.role-menu-tree-scroll :deep(.el-tree){color:#40536c;font-size:13px}.role-menu-tree-scroll :deep(.el-tree-node__content){height:36px;margin:2px 4px;padding-right:8px;border-radius:5px}.role-menu-tree-scroll :deep(.el-tree-node__content:hover){background:#f2f7fd}.role-menu-tree-scroll :deep(.el-tree-node__expand-icon){color:#8192a8}.role-menu-tree-scroll :deep(.el-checkbox){margin-right:8px}.role-menu-tree-scroll :deep(.el-checkbox__inner){width:16px;height:16px;border-radius:4px}.role-menu-node{display:flex;align-items:center;gap:9px;width:100%;min-width:0}.role-menu-node-icon{width:16px;height:16px;display:grid;place-items:center;flex:none;color:#7e92ac}.role-menu-node-icon svg{width:15px;height:15px;fill:none;stroke:currentColor;stroke-width:1.5;stroke-linecap:round;stroke-linejoin:round}.role-menu-node-label{overflow:hidden;color:#40536c;text-overflow:ellipsis;white-space:nowrap}.role-menu-child-count{margin-left:auto;padding-right:4px;color:#98a6b7;font-size:10px}.role-menu-empty{position:absolute;inset:0;z-index:1;display:grid;place-items:center;background:#fff;color:#8796a9;font-size:13px}.role-menu-tree-note{display:flex;flex:none;align-items:center;gap:7px;padding:9px 13px;border-top:1px solid #edf1f6;color:#8493a6;font-size:11px}.role-menu-info{width:14px;height:14px;display:grid;place-items:center;border:1px solid #a4b2c2;border-radius:50%;font-size:9px}.role-menu-footer{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:13px 26px;background:#fff}.role-menu-footer-note{display:flex;align-items:center;gap:7px;color:#8190a3;font-size:11px}.role-menu-footer-note strong{color:#536982;font-weight:650}.role-menu-footer-actions{display:flex;gap:9px}.role-menu-footer-actions :deep(.el-button){height:36px;margin:0;padding:0 16px;border-radius:6px;font-size:13px;font-weight:600}
@media(max-width:700px){:global(.role-menu-dialog.el-dialog){max-height:calc(100dvh - 24px);margin-top:12px!important;border-radius:9px}.role-menu-header{padding:16px 16px 13px}.role-menu-toolbar{align-items:stretch;flex-direction:column;padding:10px 14px;gap:8px}.role-menu-search{width:100%}.role-menu-tools{flex-wrap:wrap}.role-menu-tree-area{padding:10px 12px}.role-menu-tree-caption{padding:0 10px}.role-menu-tree-caption span:last-child{font-size:10px}.role-menu-footer{padding:11px 14px}}
@media(max-width:430px){.role-menu-title-row{gap:8px}.role-menu-header h2{font-size:17px}.role-menu-role{max-width:120px;font-size:11px}.role-menu-header>p,.role-menu-summary{margin-left:0}.role-menu-tools :deep(.el-button){flex:1;padding:0 7px;font-size:11px}.role-menu-footer{align-items:flex-start;flex-direction:column}.role-menu-footer-actions{align-self:flex-end}}
</style>
