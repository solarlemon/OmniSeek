<script setup lang="ts">
import { $t } from '@/locales';

defineOptions({
  name: 'GlobalLogo'
});

interface Props {
  /** Whether to show the title */
  showTitle?: boolean;
}

withDefaults(defineProps<Props>(), {
  showTitle: true
});
</script>

<template>
  <RouterLink to="/" class="w-full flex-y-center justify-start pl-6px nowrap-hidden">
    <SystemLogo class="block h-240px w-auto shrink-0" />
  </RouterLink>
</template>

<style scoped></style>
