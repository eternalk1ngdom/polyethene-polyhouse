<script setup lang="ts">
import ToolComingSoon from '../components/ToolComingSoon.vue'
import { toolRegistry } from '../config/toolRegistry'

const tool = toolRegistry.find((entry) => entry.code === 'target-kb')
</script>

<template>
  <ToolComingSoon v-if="tool" :tool="tool" />
</template>

<style scoped></style>
