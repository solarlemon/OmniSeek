<script setup lang="ts">
import { NScrollbar } from 'naive-ui';
import { VueMarkdownItProvider } from 'vue-markdown-shiki';
import ChatMessage from './chat-message.vue';

defineOptions({
  name: 'ChatList'
});

interface Props {
  messages: Api.Chat.Message[];
  loading?: boolean;
  bindScroll?: boolean;
  emptyText?: string;
}

const props = withDefaults(defineProps<Props>(), {
  loading: false,
  bindScroll: false,
  emptyText: '暂无对话'
});

const chatStore = useChatStore();
const scrollbarRef = ref<InstanceType<typeof NScrollbar>>();

function scrollToBottom() {
  setTimeout(() => {
    scrollbarRef.value?.scrollBy({
      top: 999999999999999,
      behavior: 'auto'
    });
  }, 100);
}

watch(
  () => [...props.messages],
  () => {
    scrollToBottom();
  }
);

watch(
  () => props.bindScroll,
  value => {
    if (value) {
      chatStore.scrollToBottom = scrollToBottom;
    } else if (chatStore.scrollToBottom === scrollToBottom) {
      chatStore.scrollToBottom = null;
    }
  },
  { immediate: true }
);
</script>

<template>
  <Suspense>
    <NScrollbar ref="scrollbarRef" class="h-0 flex-auto">
      <NSpin :show="props.loading">
        <VueMarkdownItProvider>
          <ChatMessage v-for="(item, index) in props.messages" :key="index" :msg="item" />
        </VueMarkdownItProvider>
        <NEmpty v-if="!props.loading && !props.messages.length" :description="props.emptyText" class="mt-40" />
      </NSpin>
    </NScrollbar>
  </Suspense>
</template>

<style scoped lang="scss"></style>
