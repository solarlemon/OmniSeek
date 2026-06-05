<script setup lang="ts">
defineOptions({
  name: 'ChatHistory'
});

const router = useRouter();

onMounted(() => {
  router.replace({ name: 'chat' });
});
</script>

<template>
  <div class="h-full"></div>
</template>

<style scoped lang="scss"></style>
