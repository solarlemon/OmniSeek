<script setup lang="ts">
import { VueMarkdownIt } from 'vue-markdown-shiki';
import { formatDate } from '@/utils/common';
defineOptions({ name: 'ChatMessage' });

const props = defineProps<{ msg: Api.Chat.Message }>();

const authStore = useAuthStore();

function handleCopy(content: string) {
  navigator.clipboard.writeText(content);
  window.$message?.success('已复制');
}

const chatStore = useChatStore();

// 处理来源文件链接的函数
function processSourceLinks(text: string): string {
  // 匹配 (来源#数字: 文件名) 的正则表达式
  const sourcePattern = /\(来源#(\d+):\s*([^)]+)\)/g;
  
  return text.replace(sourcePattern, (match, sourceNum, fileName) => {
    // 为文件名创建可点击的链接
    const linkClass = 'source-file-link';
    const encodedFileName = encodeURIComponent(fileName.trim());
    // 生成唯一的ID用于事件绑定
    const linkId = `source-link-${Math.random().toString(36).substr(2, 9)}`;
    
    // 在下一个tick中绑定事件
    nextTick(() => {
      const linkElement = document.getElementById(linkId);
      if (linkElement) {
        linkElement.addEventListener('click', () => handleSourceFileClick(encodedFileName));
      }
    });
    
    return `(来源#${sourceNum}: <span id="${linkId}" class="${linkClass}" data-filename="${encodedFileName}">${fileName}</span>)`;
  });
}

const content = computed(() => {
  chatStore.scrollToBottom?.();
  const rawContent = props.msg.content ?? '';
  
  // 只对助手消息处理来源链接
  if (props.msg.role === 'assistant') {
    return processSourceLinks(rawContent);
  }
  
  return rawContent;
});

// 处理来源文件点击事件
async function handleSourceFileClick(fileName: string) {
  const decodedFileName = decodeURIComponent(fileName);
  console.log('点击了来源文件:', decodedFileName);
  
  try {
    window.$message?.loading(`正在获取文件下载链接: ${decodedFileName}`, {
      duration: 0,
      closable: false
    });
    
    // 调用文件下载接口
    const { error, data } = await request<Api.Document.DownloadResponse>({
      url: 'documents/download',
      params: { fileName: decodedFileName },
      baseURL: 'proxy-api'
    });
    
    window.$message?.destroyAll();
    
    if (error) {
      window.$message?.error(`文件下载失败: ${error.response?.data?.message || '未知错误'}`);
      return;
    }
    
    if (data?.downloadUrl) {
      // 在新窗口打开下载链接
      window.open(data.downloadUrl, '_blank');
      window.$message?.success(`文件下载链接已打开: ${decodedFileName}`);
    } else {
      window.$message?.error('未能获取到下载链接');
    }
  } catch (err) {
    window.$message?.destroyAll();
    console.error('文件下载失败:', err);
    window.$message?.error(`文件下载失败: ${decodedFileName}`);
  }
}
</script>

<template>
  <div class="mb-8 flex-col gap-2">
    <div v-if="msg.role === 'user'" class="flex items-center gap-4">
      <NAvatar class="bg-success">
        <SvgIcon icon="ph:user-circle" class="text-icon-large color-white" />
      </NAvatar>
      <div class="flex-col gap-1">
        <NText class="text-4 font-bold">{{ authStore.userInfo.username }}</NText>
        <NText class="text-3 color-gray-500">{{ formatDate(msg.timestamp) }}</NText>
      </div>
    </div>
    <div v-else class="flex items-center gap-4">
      <NAvatar :size="36" class="bg-[#E0E7FF] p-1.5">
        <img src="/favicon.svg" alt="万象智搜" class="h-6 w-6 object-contain" />
      </NAvatar>
      <div class="flex-col gap-1">
        <NText class="text-4 font-bold">万象智搜</NText>
        <NText class="text-3 color-gray-500">{{ formatDate(msg.timestamp) }}</NText>
      </div>
    </div>
    <NText v-if="msg.status === 'pending'">
      <icon-eos-icons:three-dots-loading class="ml-12 mt-2 text-8" />
    </NText>
    <NText v-else-if="msg.status === 'error'" class="ml-12 mt-2 italic">服务器繁忙，请稍后再试</NText>
    <div v-else-if="msg.role === 'assistant'" class="mt-2 pl-12">
      <VueMarkdownIt :content="content" />
    </div>
    <NText v-else-if="msg.role === 'user'" class="ml-12 mt-2 text-4">{{ content }}</NText>
    <NDivider class="ml-12 w-[calc(100%-3rem)] mb-0! mt-2!" />
    <div class="ml-12 flex gap-4">
      <NButton quaternary @click="handleCopy(msg.content)">
        <template #icon>
          <icon-mynaui:copy />
        </template>
      </NButton>
    </div>
  </div>
</template>

<style scoped lang="scss">
:deep(.source-file-link) {
  color: #1890ff;
  cursor: pointer;
  text-decoration: underline;
  transition: color 0.2s;
  
  &:hover {
    color: #40a9ff;
    text-decoration: none;
  }
  
  &:active {
    color: #096dd9;
  }
}
</style>
