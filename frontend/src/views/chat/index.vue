<script setup lang="ts">
import dayjs from 'dayjs';
import ChatList from './modules/chat-list.vue';
import InputBox from './modules/input-box.vue';

interface ChatSession {
  id: string;
  title: string;
  timestamp?: string;
}

const authStore = useAuthStore();
const chatStore = useChatStore();
const { list, input } = storeToRefs(chatStore);

const historySessions = ref<any[]>([]);
const sessionMessages = ref<Record<string, Api.Chat.Message[]>>({});
const historyLoading = ref(false);
const sessionLoading = ref(false);

const range = ref<[number, number]>([dayjs().subtract(7, 'day').valueOf(), dayjs().add(1, 'day').valueOf()]);
const userId = ref<number>(authStore.userInfo.id);

const isAdmin = computed(() => authStore.isAdmin);

const historyParams = computed(() => {
  if (isAdmin.value) {
    return {
      userid: userId.value
    };
  }

  return {};
});

function parseTime(value: unknown) {
  if (!value) return undefined;
  const time = dayjs(value as string);
  return time.isValid() ? time.toISOString() : undefined;
}

function normalizeMessage(item: any): Api.Chat.Message {
  const role = item?.role === 'assistant' ? 'assistant' : 'user';
  return {
    role,
    content: item?.content || item?.text || item?.message || '',
    status: item?.status,
    timestamp: item?.timestamp || item?.createTime || item?.createdAt
  };
}

function getSessionId(item: any) {
  return item?.sessionId || item?.id || item?.conversationId || '';
}

function getSessionTitle(item: any) {
  const title = item?.title || item?.name || item?.sessionTitle;
  if (title) return title;
  const time = getSessionTime(item);
  return time ? `对话 ${dayjs(time).format('YYYY-MM-DD HH:mm')}` : '未命名对话';
}

function getSessionTime(item: any) {
  return parseTime(item?.updatedAt || item?.updateTime || item?.timestamp || item?.createdAt || item?.createTime);
}

async function fetchHistory() {
  if (isAdmin.value && !userId.value) return;

  historyLoading.value = true;
  console.log('🚀 开始获取会话列表...');
  const { error, data } = await request<any[]>({
    url: 'chat-sessions',
    params: historyParams.value
  });
  
  console.log('📥 获取到的原始响应:', { error, data });
  
  if (!error) {
    console.log('📊 开始解析数据:', data);
    historySessions.value = Array.isArray(data)
      ? data
      : Array.isArray((data as any)?.content)
        ? (data as any).content
        : Array.isArray((data as any)?.data)
          ? (data as any).data
          : [];
  }
  
  console.log('✅ 解析后的会话列表:', historySessions.value);
  historyLoading.value = false;
}

watch([range, userId], () => {
  fetchHistory();
}, { immediate: true });

const sessions = computed<ChatSession[]>(() => {
  console.log('🔄 计算会话列表，原始数据:', historySessions.value);
  const [start, end] = range.value;
  console.log('📅 时间范围:', { start, end });

  const result = historySessions.value
    .map(item => {
      const timestamp = getSessionTime(item);
      const id = getSessionId(item);
      const title = getSessionTitle(item);
      console.log('📦 处理单个会话:', { item, id, title, timestamp });
      return { id, title, timestamp };
    })
    .filter(session => {
      const hasId = Boolean(session.id);
      if (!hasId) console.log('❌ 过滤掉没有 id 的会话:', session);
      return hasId;
    })
    .filter(session => {
      if (!session.timestamp) return true;
      const time = dayjs(session.timestamp).valueOf();
      const inRange = time >= start && time <= end + 24 * 60 * 60 * 1000;
      if (!inRange) console.log('⏰ 会话不在时间范围内:', session, { time, start, end });
      return inRange;
    })
    .sort((a, b) => dayjs(b.timestamp).valueOf() - dayjs(a.timestamp).valueOf());

  console.log('✅ 最终会话列表:', result);
  return result;
});

const selectedSessionId = ref<'current' | string>('current');

const selectedMessages = computed(() => {
  if (selectedSessionId.value === 'current') return list.value;
  return sessionMessages.value[selectedSessionId.value] || [];
});

const isHistoryView = computed(() => selectedSessionId.value !== 'current');

async function fetchSessionDetail(id: string) {
  if (!id) return;

  sessionLoading.value = true;

  // 直接调用 users/conversation/${id} 获取历史消息
  const { error, data } = await request<any>({
    url: `users/conversation/${id}`
  });

  let records: any[] = [];

  if (!error) {
    records = Array.isArray(data)
      ? data
      : Array.isArray(data?.data)
        ? data.data
        : Array.isArray(data?.content)
          ? data.content
        : [];
  }

  if (records.length) {
    sessionMessages.value = {
      ...sessionMessages.value,
      [id]: records.map(normalizeMessage)
    };
  } else {
    sessionMessages.value = {
      ...sessionMessages.value,
      [id]: []
    };
  }

  sessionLoading.value = false;
}

function handleNewChat() {
  selectedSessionId.value = 'current';
  list.value = [];
  input.value.message = '';
  chatStore.conversationId = '';
}

function handleSelectSession(id: string) {
  selectedSessionId.value = id;
  if (!sessionMessages.value[id]) {
    fetchSessionDetail(id);
  }
}

watch(
  () => chatStore.conversationId,
  value => {
    if (value) {
      fetchHistory();
    }
  }
);
</script>

<template>
  <div class="h-full flex gap-4">
    <aside class="h-full w-260px shrink-0 flex-col gap-3 rounded-12px bg-white p-4 shadow-sm dark:bg-#1c1c1c">
      <NButton type="primary" class="w-full" @click="handleNewChat">新对话</NButton>
      <div v-if="isAdmin" class="flex-col gap-2">
        <NText class="text-12px color-gray-500">用户</NText>
        <TheSelect
          v-model:value="userId"
          url="admin/users/list"
          :params="{ page: 1, size: 999, orgTag: authStore.userInfo.primaryOrg }"
          key-field="content"
          value-field="userId"
          label-field="username"
          class="clear w-full"
          :clearable="false"
        />
      </div>
      <div class="flex-col gap-2">
        <NText class="text-12px color-gray-500">时间</NText>
        <NDatePicker v-model:value="range" type="daterange" class="w-full" />
      </div>
      <div class="flex-col gap-2 flex-1 min-h-0">
        <NText class="text-12px color-gray-500">历史对话</NText>
        <NScrollbar class="h-0 flex-auto">
          <div class="flex-col gap-2">
            <NSpin :show="historyLoading">
              <button
                v-for="session in sessions"
                :key="session.id"
                type="button"
                class="w-full rounded-8px px-3 py-2 text-left transition"
                :class="
                  selectedSessionId === session.id
                    ? 'bg-primary text-white'
                    : 'bg-#f6f7fb text-#333 hover:bg-#eceffa dark:bg-#2a2a2a dark:text-#f1f1f1'
                "
                @click="handleSelectSession(session.id)"
              >
                <div class="truncate text-14px font-600">{{ session.title }}</div>
                <div class="text-12px opacity-70">
                  {{ session.timestamp ? dayjs(session.timestamp).format('YYYY-MM-DD HH:mm') : '未知时间' }}
                </div>
              </button>
              <div v-if="!historyLoading && !sessions.length" class="py-4 text-center text-12px color-gray-500">
                暂无历史对话
              </div>
            </NSpin>
          </div>
        </NScrollbar>
      </div>
    </aside>
    <section class="h-full flex-1 flex-col gap-4">
      <ChatList
        :messages="selectedMessages"
        :loading="isHistoryView ? historyLoading || sessionLoading : false"
        :bind-scroll="!isHistoryView"
        :empty-text="isHistoryView ? '暂无历史对话内容' : '开始新的对话吧'"
      />
      <InputBox v-if="!isHistoryView" />
    </section>
  </div>
</template>

<style scoped></style>
