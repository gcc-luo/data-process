<script setup>
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import { iconPaths } from './orch-icons.js'
import { activeOutputPorts } from './orch-graph.js'

const props = defineProps({ data: Object })
const node = computed(() => props.data.node)
const component = computed(() => props.data.component)
const category = computed(() => props.data.category)
const outPorts = computed(() => activeOutputPorts(component.value, node.value.config))
const inPorts = computed(() => component.value?.inputPorts || [])

const CATEGORY_META = {
  SOURCE: { label: '来源组件', cls: 'cat-source' },
  TRANSFORM: { label: '处理组件', cls: 'cat-transform' },
  SINK: { label: '目的组件', cls: 'cat-sink' },
  CONTROL: { label: '控制组件', cls: 'cat-control' },
  JOB: { label: '作业组件', cls: 'cat-control' }
}
const meta = computed(() => CATEGORY_META[category.value] || CATEGORY_META.TRANSFORM)

const status = computed(() => {
  const s = props.data.state
  if (s === 'RUNNING') return { cls: 'st-running', text: '运行中', icon: 'spin' }
  if (s === 'SUCCEEDED') return { cls: 'st-success', text: '成功', icon: 'check' }
  if (s === 'FAILED') return { cls: 'st-failed', text: '失败', icon: 'cross' }
  if (s === 'SKIPPED') return { cls: 'st-skipped', text: '跳过', icon: 'skip' }
  if (!props.data.enabled) return { cls: 'st-disabled', text: '禁用', icon: 'skip' }
  if (props.data.hasIssue) return { cls: 'st-warning', text: '待修正', icon: 'warn' }
  if (props.data.configured) return { cls: 'st-ready', text: '已配置', icon: 'check' }
  return { cls: 'st-empty', text: '待配置', icon: 'warn' }
})
</script>

<template>
  <div class="orch-node" :class="[meta.cls, { selected: data.selected, disabled: !data.enabled, failed: data.state === 'FAILED', running: data.state === 'RUNNING' }]">
    <Handle v-for="(p, i) in inPorts" :key="'in-' + p.id" :id="p.id" type="target" :position="Position.Left"
            :style="{ top: inPorts.length > 1 ? `${20 + i * 22}px` : '50%' }" class="orch-handle" :class="{ control: p.kind === 'CONTROL' }" />
    <div class="node-head">
      <span class="node-icon">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path v-for="(d, i) in iconPaths(component?.icon)" :key="i" :d="d" /></svg>
      </span>
      <div class="node-titles">
        <strong class="node-name" :title="node.description ? node.name + ' — ' + node.description : node.name">{{ node.name }}</strong>
        <span class="node-type">{{ meta.label }}</span>
      </div>
      <span class="node-status" :class="status.cls" :title="status.text">
        <svg v-if="status.icon === 'check'" viewBox="0 0 16 16"><path d="m3.5 8.5 3 3 6-7" /></svg>
        <svg v-else-if="status.icon === 'cross'" viewBox="0 0 16 16"><path d="M4 4l8 8M12 4l-8 8" /></svg>
        <svg v-else-if="status.icon === 'warn'" viewBox="0 0 16 16"><path d="M8 2 1.5 13.5h13L8 2ZM8 6.5v3M8 11.5h.01" /></svg>
        <svg v-else-if="status.icon === 'skip'" viewBox="0 0 16 16"><path d="M3 8h10" /></svg>
        <span v-else class="spinner"></span>
      </span>
    </div>
    <div class="node-summary" :title="data.summary">{{ data.summary || '未配置' }}</div>
    <div v-if="outPorts.length > 1" class="node-ports">
      <div v-for="p in outPorts" :key="p.id" class="port-row">
        <span class="port-label">{{ p.label || p.id }}</span>
        <Handle :id="p.id" type="source" :position="Position.Right" class="orch-handle port-handle" />
      </div>
    </div>
    <Handle v-else-if="outPorts.length === 1" :id="outPorts[0].id" type="source" :position="Position.Right" class="orch-handle" />
  </div>
</template>

<style scoped>
.orch-node{position:relative;width:212px;border-radius:8px;border:1px solid var(--el-border-color-lighter);background:#fff;padding:10px 12px;box-shadow:0 1px 3px rgba(20,40,70,.06);font-size:12px;transition:box-shadow .15s,border-color .15s}
.orch-node.selected{border-color:var(--el-color-primary);box-shadow:0 0 0 2px rgba(64,158,255,.25),0 4px 14px rgba(20,40,70,.12)}
.orch-node.disabled{opacity:.6}
.orch-node.failed{border-color:var(--el-color-danger)}
.orch-node.running{border-color:var(--el-color-primary)}
.cat-source{background:linear-gradient(180deg,#f0f7ff,#fff)}
.cat-transform{background:linear-gradient(180deg,#f6f2ff,#fff)}
.cat-sink{background:linear-gradient(180deg,#f0faf2,#fff)}
.cat-control{background:linear-gradient(180deg,#fff7ec,#fff)}
.node-head{display:flex;align-items:center;gap:8px}
.node-icon{flex:none;width:26px;height:26px;border-radius:6px;display:flex;align-items:center;justify-content:center;background:rgba(255,255,255,.7);border:1px solid var(--el-border-color-lighter)}
.node-icon svg{width:17px;height:17px;fill:none;stroke:#5a6b82;stroke-width:1.5;stroke-linecap:round;stroke-linejoin:round}
.cat-source .node-icon svg{stroke:#2f7fd1}
.cat-transform .node-icon svg{stroke:#8250df}
.cat-sink .node-icon svg{stroke:#2f9e57}
.cat-control .node-icon svg{stroke:#d98a2b}
.node-titles{flex:1;min-width:0;display:flex;flex-direction:column}
.node-name{font-size:13px;color:#24344d;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.node-type{font-size:11px;color:#8a94a6}
.node-status{flex:none;width:18px;height:18px;border-radius:50%;display:flex;align-items:center;justify-content:center}
.node-status svg{width:12px;height:12px;fill:none;stroke:#fff;stroke-width:2;stroke-linecap:round;stroke-linejoin:round}
.st-ready,.st-success{background:#3aa76d}
.st-warning,.st-empty{background:#e6a23c}
.st-failed{background:#e05656}
.st-running{background:#409eff}
.st-skipped,.st-disabled{background:#b6bfc9}
.spinner{width:10px;height:10px;border:2px solid rgba(255,255,255,.5);border-top-color:#fff;border-radius:50%;animation:orch-spin .8s linear infinite}
@keyframes orch-spin{to{transform:rotate(360deg)}}
.node-summary{margin-top:7px;color:#5a6b82;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;background:rgba(255,255,255,.6);border-radius:4px;padding:3px 6px}
.node-ports{margin-top:6px;display:flex;flex-direction:column;gap:2px}
.port-row{position:relative;display:flex;justify-content:flex-end;align-items:center;height:18px}
.port-label{font-size:11px;color:#8a94a6;padding-right:6px}
.orch-handle{width:9px;height:9px;background:#fff;border:2px solid #7aa7d6}
.orch-handle.control{border-color:#e6a23c}
.port-handle{position:absolute;right:-17px;top:50%}
</style>