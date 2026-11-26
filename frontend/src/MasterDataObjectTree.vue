<script setup>
import { computed, ref } from 'vue'
import DirectoryPanelHeader from './DirectoryPanelHeader.vue'

const props = defineProps({ tree: { type: Object, default: () => ({ directories: [], objects: [] }) }, selectedId: { type: Number, default: 0 } })
const emit = defineEmits(['select'])
const search = ref('')

const nodes = computed(() => {
  const term = search.value.trim().toLocaleLowerCase()
  const directories = Array.isArray(props.tree?.directories) ? props.tree.directories : []
  const objects = Array.isArray(props.tree?.objects) ? props.tree.objects : []
  const byParent = new Map()
  for (const directory of directories) {
    const parent = directory.parentId ?? null
    if (!byParent.has(parent)) byParent.set(parent, [])
    byParent.get(parent).push(directory)
  }
  const objectNodes = parentId => objects.filter(object => object.directoryId === parentId)
    .sort((a, b) => String(a.name).localeCompare(String(b.name), 'zh-CN') || a.id - b.id)
    .filter(object => !term || `${object.name} ${object.identity?.table} ${object.sourceName}`.toLocaleLowerCase().includes(term))
    .map(object => ({ ...object, key: `object-${object.id}`, kind: 'object', label: object.name || object.identity?.table || `对象 ${object.id}` }))
  const build = parentId => (byParent.get(parentId) || [])
    .slice().sort((a, b) => a.sortOrder - b.sortOrder || a.id - b.id)
    .map(directory => {
      const children = [...build(directory.id), ...objectNodes(directory.id)]
      const matches = !term || directory.name.toLocaleLowerCase().includes(term)
      return { ...directory, key: `directory-${directory.id}`, kind: 'directory', label: directory.name, children, _matches: matches }
    })
    .filter(directory => !term || directory._matches || directory.children.length)
  const children = [...build(null), ...objectNodes(null)]
  return [{ key: 'all-objects', id: 0, kind: 'root', label: '全部数据对象', children }]
})

const currentKey = computed(() => props.selectedId ? `object-${props.selectedId}` : 'all-objects')
const expanded = computed(() => nodes.value.flatMap(function flatten(node) { return [node.key, ...(node.children || []).flatMap(flatten)] }))
function select(node) { if (node.kind === 'object') emit('select', node.id); else if (node.kind === 'root') emit('select', 0) }
</script>

<template>
  <aside class="master-directory catalog-panel">
    <DirectoryPanelHeader v-model="search" title="全部数据对象" placeholder="搜索表名或中文名" />
    <el-tree
      class="master-tree directory-tree"
      :data="nodes"
      node-key="key"
      :props="{ label: 'label', children: 'children' }"
      :current-node-key="currentKey"
      :default-expanded-keys="expanded"
      :expand-on-click-node="false"
      highlight-current
      @node-click="select"
    >
      <template #default="{ data }">
        <span class="master-tree-node" :class="{ 'is-object': data.kind === 'object' }">
          <svg v-if="data.kind === 'directory'" class="directory-folder-icon" viewBox="0 0 16 14" aria-hidden="true"><path d="M1 2.5c0-.8.7-1.5 1.5-1.5h3l1.6 1.7h6.4c.8 0 1.5.7 1.5 1.5v7.1c0 .9-.7 1.6-1.6 1.6H2.6c-.9 0-1.6-.7-1.6-1.6Z"/><path class="folder-highlight" d="M1.4 4.1h13"/></svg>
          <svg v-else-if="data.kind === 'object'" class="object-table-icon" viewBox="0 0 16 16" aria-hidden="true"><rect x="1.6" y="2" width="12.8" height="12" rx="1.6"/><path d="M1.8 6h12.4M1.8 10h12.4M6 2.2v11.6M10 2.2v11.6"/></svg>
          <span class="master-tree-label" :title="data.kind === 'object' ? `${data.name} · ${data.identity?.table}` : data.label">{{ data.label }}</span>
          <span v-if="data.kind === 'object'" class="master-tree-meta" :title="`${data.identity?.type === 'VIEW' ? '视图' : '数据表'} · ${data.identity?.table}`">{{ data.identity?.type === 'VIEW' ? '视图' : '表' }}</span>
        </span>
      </template>
    </el-tree>
  </aside>
</template>

<style scoped>
.master-directory{flex:0 0 clamp(220px,22%,300px);min-width:190px;min-height:0;display:flex;flex-direction:column;overflow:hidden;background:#fff;border:1px solid var(--el-border-color-lighter);border-radius:4px}
.master-tree{flex:1;min-height:0;overflow:auto;padding:8px 6px 12px}
.master-tree-node{display:flex;align-items:center;gap:7px;width:100%;min-width:0;color:var(--ui-text-body)}
.master-tree-label{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.master-tree-meta{flex:none;color:var(--ui-text-muted);font-size:11px}
.directory-folder-icon{width:15px;height:14px;flex:none;color:#c3c9d1;fill:currentColor}
.folder-highlight{fill:none;stroke:#fff;stroke-opacity:.72;stroke-width:.85}
.object-table-icon{width:14px;height:14px;flex:none;fill:none;stroke:#8498ae;stroke-width:1.2;stroke-linecap:round;stroke-linejoin:round}
.master-tree :deep(.el-tree-node.is-current > .el-tree-node__content){background:#eaf2fc;color:#286eae}
.master-tree :deep(.el-tree-node.is-current > .el-tree-node__content .object-table-icon){stroke:#367fbd}
.master-tree :deep(.el-tree-node__content){height:34px;border-radius:4px}
.master-tree :deep(.el-tree-node__content:hover){background:#f4f7fb}
@media(max-width:760px){.master-directory{flex:none;width:100%;min-width:0;max-height:270px}}
</style>
