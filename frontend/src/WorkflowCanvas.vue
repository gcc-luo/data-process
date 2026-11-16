<script setup>
import { computed } from 'vue'
import { VueFlow } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import { flowNodes, flowEdges } from './flow-adapter.js'
const props = defineProps({ graph: { type: Object, required: true }, readonly: { type: Boolean, default: false } })
const emit = defineEmits(['move', 'connect', 'select'])
const nodes = computed(() => flowNodes(props.graph.tasks))
const edges = computed(() => flowEdges(props.graph.edges))
function dragStop(event) { for (const node of event.nodes?.length ? event.nodes : [event.node]) emit('move', { id: node.id, x: node.position.x, y: node.position.y }) }
function connect(event) { if (event.source && event.target) emit('connect', { from: event.source, to: event.target }) }
</script>
<template>
  <div class="canvas" aria-label="工作流拓扑画布">
    <VueFlow :nodes="nodes" :edges="edges" :nodes-draggable="!readonly" :nodes-connectable="!readonly" :delete-key-code="null" :zoom-on-scroll="false" :min-zoom="0.5" :max-zoom="1.5" fit-view-on-init @connect="connect" @node-drag-stop="dragStop" @node-click="emit('select', $event.node.id)">
      <Background :gap="20" :size="1" pattern-color="#d6e4e1" />
      <Controls position="bottom-right" :show-interactive="false" />
      <div v-if="!nodes.length" class="empty">添加节点后，可拖动调整位置并连接依赖。</div>
    </VueFlow>
  </div>
</template>
<style scoped>
.canvas{height:520px;overflow:hidden;border:1px solid #cbdcda;border-radius:8px;background:#f8fbfa}.empty{position:absolute;top:44%;left:0;right:0;text-align:center;color:#718581;pointer-events:none}:deep(.vue-flow__node){border:1px solid #79aaa1;border-left:5px solid #208b77;border-radius:7px;background:#fff;padding:10px 15px;min-width:170px;box-shadow:0 3px 12px #173e3615}:deep(.vue-flow__node.selected){outline:2px solid #208b77}:deep(.vue-flow__edge-path){stroke:#728a88;stroke-width:2}:deep(.vue-flow__handle){width:10px;height:10px;background:#208b77;border:2px solid white}
</style>
