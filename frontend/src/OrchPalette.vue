<script setup>
import { computed, ref } from 'vue'
import { iconPaths } from './orch-icons.js'
import { CATEGORY_LABELS, CATEGORY_ORDER } from './orch-graph.js'

const props = defineProps({ components: Array, disabled: Boolean })
const emit = defineEmits(['add'])
const search = ref('')
const collapsed = ref({})

const groups = computed(() => {
  const keyword = search.value.trim().toLowerCase()
  const match = c => !keyword || [c.name, c.type, c.description, ...(c.tags || [])].join(' ').toLowerCase().includes(keyword)
  return CATEGORY_ORDER
    .map(cat => ({ cat, label: CATEGORY_LABELS[cat], items: (props.components || []).filter(c => c.category === cat && match(c)) }))
    .filter(g => g.items.length)
})

function toggle(cat) { collapsed.value[cat] = !collapsed.value[cat] }
function onDragStart(event, c) {
  if (props.disabled || !c.enabled) { event.preventDefault(); return }
  event.dataTransfer.setData('application/orch-component', c.type)
  event.dataTransfer.effectAllowed = 'copy'
}
function onClick(c) {
  if (props.disabled || !c.enabled) return
  emit('add', c.type)
}
</script>

<template>
  <aside class="palette">
    <div class="palette-head">
      <svg viewBox="0 0 24 24" class="palette-logo"><path d="M4 7c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3ZM4 7v10c0 1.7 3.6 3 8 3s8-1.3 8-3V7M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3" /></svg>
      <strong>组件库</strong>
    </div>
    <el-input v-model="search" clearable placeholder="搜索组件" size="small" class="palette-search">
      <template #prefix><svg viewBox="0 0 24 24" class="search-icon"><path d="M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14ZM20 20l-4-4" /></svg></template>
    </el-input>
    <div class="palette-groups">
      <div v-for="g in groups" :key="g.cat" class="palette-group">
        <button type="button" class="group-head" @click="toggle(g.cat)">
          <span class="group-title">{{ g.label }}<span class="group-count">{{ g.items.length }}</span></span>
          <svg viewBox="0 0 20 20" class="chevron" :class="{ collapsed: collapsed[g.cat] }"><path d="m5 7.5 5 5 5-5" /></svg>
        </button>
        <div v-show="!collapsed[g.cat]" class="group-items">
          <button v-for="c in g.items" :key="c.type" type="button" class="palette-item" :class="{ disabled: !c.enabled || disabled }"
                  :draggable="c.enabled && !disabled" :title="c.enabled ? c.description : (c.note || '即将支持')"
                  @dragstart="onDragStart($event, c)" @click="onClick(c)">
            <span class="item-icon" :class="'ic-' + c.color">
              <svg viewBox="0 0 24 24"><path v-for="(d, i) in iconPaths(c.icon)" :key="i" :d="d" /></svg>
            </span>
            <span class="item-name">{{ c.name }}</span>
            <span v-if="!c.enabled" class="soon">待支持</span>
          </button>
        </div>
      </div>
      <p v-if="!groups.length" class="palette-empty">没有匹配的组件</p>
    </div>
    <p class="palette-tip">拖拽或点击加入画布</p>
  </aside>
</template>

<style scoped>
.palette{flex:0 0 212px;display:flex;flex-direction:column;min-height:0;overflow:hidden;background:#fff;border:1px solid var(--el-border-color-lighter);border-radius:8px;padding:10px}
.palette-head{display:flex;align-items:center;gap:7px;margin-bottom:8px}
.palette-head strong{font-size:13px;color:#24344d}
.palette-logo{width:18px;height:18px;fill:none;stroke:#2f7fd1;stroke-width:1.5}
.palette-search{margin-bottom:8px}
.search-icon{width:14px;height:14px;fill:none;stroke:#9aa4b2;stroke-width:1.6}
.palette-groups{flex:1;min-height:0;overflow-y:auto;overflow-x:hidden;display:flex;flex-direction:column;gap:4px;padding-right:2px}
.palette-groups{scrollbar-width:thin;scrollbar-color:#aebfd0 transparent}
.palette-groups::-webkit-scrollbar{width:4px}
.palette-groups::-webkit-scrollbar-track{background:transparent}
.palette-groups::-webkit-scrollbar-thumb{background:#aebfd0;border-radius:999px}
.palette-groups::-webkit-scrollbar-thumb:hover{background:#839eb8}
.palette-groups::-webkit-scrollbar-button{display:none}
.palette-group{display:flex;flex-direction:column}
.group-head{display:flex;align-items:center;justify-content:space-between;width:100%;border:0;background:transparent;padding:5px 4px;cursor:pointer;color:#5a6b82;font-size:12px;font-weight:600}
.group-title{display:flex;align-items:center;gap:6px}
.group-count{font-size:11px;font-weight:500;color:#9aa4b2;background:#f0f2f5;border-radius:8px;padding:0 6px;line-height:16px}
.chevron{width:15px;height:15px;fill:none;stroke:#9aa4b2;stroke-width:1.6;transition:transform .15s}
.chevron.collapsed{transform:rotate(-90deg)}
.group-items{display:flex;flex-direction:column;gap:2px;padding:1px 0 5px}
.palette-item{display:flex;align-items:center;gap:8px;width:100%;height:30px;padding:0 8px;border:1px solid transparent;border-radius:6px;background:transparent;cursor:grab;text-align:left;transition:background .12s,border-color .12s}
.palette-item:hover:not(.disabled){background:#ecf5ff;border-color:#d3e6fb}
.palette-item:active:not(.disabled){cursor:grabbing}
.palette-item.disabled{cursor:not-allowed;opacity:.5}
.item-icon{flex:none;width:18px;height:18px;display:flex;align-items:center;justify-content:center}
.item-icon svg{width:17px;height:17px;fill:none;stroke:#5a6b82;stroke-width:1.5;stroke-linecap:round;stroke-linejoin:round}
.ic-blue svg{stroke:#2f7fd1}.ic-purple svg{stroke:#8250df}.ic-green svg{stroke:#2f9e57}.ic-orange svg{stroke:#d98a2b}
.item-name{flex:1;min-width:0;font-size:12px;color:#33415c;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.soon{flex:none;font-size:10px;color:#b08a3e;background:#fdf3e0;border-radius:3px;padding:0 4px;line-height:15px}
.palette-empty{color:#9aa4b2;font-size:12px;text-align:center;padding:16px 0}
.palette-tip{flex:none;margin-top:8px;color:#9aa4b2;font-size:11px}
@media(max-width:900px){.palette{flex:none;width:100%;max-height:260px}}
</style>
