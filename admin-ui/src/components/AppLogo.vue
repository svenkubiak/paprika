<script setup lang="ts">
import logo50 from '@/assets/paprika-logo-50.png'
import logo100 from '@/assets/paprika-logo-100.png'

withDefaults(
  defineProps<{
    size?: 'sm' | 'md' | 'lg'
    showText?: boolean
    subtitle?: string
  }>(),
  {
    size: 'md',
    showText: false,
    subtitle: ''
  }
)

const imageSrc = {
  sm: logo50,
  md: logo50,
  lg: logo100
} as const

const imageClass = {
  sm: 'size-8',
  md: 'size-11',
  lg: 'size-14'
} as const

const titleClass = {
  sm: 'brand-title text-2xl font-bold tracking-tight',
  md: 'brand-title text-3xl font-bold tracking-tight',
  lg: 'brand-title text-4xl font-bold tracking-tight'
} as const
</script>

<template>
  <div class="flex items-center gap-3">
    <img
      :src="imageSrc[size]"
      alt="Paprika"
      :class="['shrink-0 object-contain', imageClass[size]]"
    />
    <div v-if="showText" class="min-w-0">
      <div :class="titleClass[size]">Paprika</div>
      <div v-if="subtitle" class="text-xs text-muted">{{ subtitle }}</div>
    </div>
  </div>
</template>
