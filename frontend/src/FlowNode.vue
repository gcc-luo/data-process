<script setup>
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import { componentDef, configSummary } from './flow-components.js'
const props = defineProps({ data: Object, selected: Boolean })
const def = computed(() => { try { return componentDef(props.data.component) } catch { return { component: props.data.component, group: 'interceptor', label: props.data.component } } })
const summary = computed(() => { try { return configSummary(props.data.component, props.data.config || {}) } catch { return '' } })
const isSource = computed(() => def.value.group === 'source')
const isSink = computed(() => def.value.group === 'sink')
</script>
<template>
  <div class="flow-node" :class="{ selected, source: isSource, sink: isSink }">
    <Handle v-if="!isSource" type="target" :position="Position.Left" />
    <strong>{{ def.label }}</strong>
    <small>{{ data.key }} · {{ summary }}</small>
    <Handle v-if="!isSink" type="source" :position="Position.Right" />
  </div>
</template>
<style scoped>
.flow-node{border:1px solid #79aaa1;border-left:5px solid #208b77;border-radius:7px;background:#fff;padding:8px 12px;min-width:150px;box-shadow:0 3px 12px #173e3615;display:flex;flex-direction:column;gap:2px}
.flow-node.selected{outline:2px solid #208b77}
.flow-node.source{border-left-color:#3572d3}
.flow-node.sink{border-left-color:#b26a00}
.flow-node small{color:#728582;font-size:11px;max-width:180px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
</style>
