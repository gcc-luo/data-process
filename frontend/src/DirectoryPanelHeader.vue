<script setup>
defineProps({ title: { type: String, required: true }, modelValue: { type: String, default: '' }, placeholder: { type: String, default: '搜索目录' } })
defineEmits(['update:modelValue'])
</script>

<template>
  <div class="catalog-header"><div><div class="catalog-title">{{ title }}</div><div class="catalog-subtitle">按目录浏览</div></div><slot /></div>
  <div class="catalog-search"><el-input :model-value="modelValue" :placeholder="placeholder" :aria-label="placeholder" clearable @update:model-value="$emit('update:modelValue', $event)"><template #prefix><svg class="catalog-search-icon" viewBox="0 0 16 16" aria-hidden="true"><circle cx="7" cy="7" r="4.5"/><path d="m10.5 10.5 3 3"/></svg></template></el-input></div>
</template>
