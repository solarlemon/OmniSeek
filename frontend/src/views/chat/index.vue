<script setup lang="ts">
import dayjs from 'dayjs';
import ChatList from './modules/chat-list.vue';
import InputBox from './modules/input-box.vue';

interface ChatSession {
  id: string;
  title: string;
  timestamp?: string;
  messages: Api.Chat.Message[];
}

const authStore = useAuthStore();
const chatStore = useChatStore();
const { list, input } = storeToRefs(chatStore);

const historyMessages = ref<Api.Chat.Message[]>([]);
const historyLoading = ref(false);

const range = ref<[number, number]>([dayjs().subtract(7, 'day').valueOf(), dayjs().add(1, 'day').valueOf()]);
const userId = ref<number>(authStore.userInfo.id);

const isAdmin = computed(() => authStore.isAdmin);

const historyParams = computed(() => {
  const params = {
    start_date: dayjs(range.value[0]).format('YYYY-MM-DD'),
    end_date: dayjs(range.value[1]).format('YYYY-MM-DD')
  };

  if (isAdmin.value) {
    return {
      ...params,
      userid: userId.value
    };
  }

  return params;
});

async function fetchHistory() {
  if (isAdmin.value && !userId.value) return;

  historyLoading.value = true;
  const { error, data } = await request<Api.Chat.Message[]>({
    url: isAdmin.value ? 'admin/conversation' : 'users/conversation',
    params: historyParams.value
  });
  if (!error) {
    historyMessages.value = data || [];
  }
  historyLoading.value = false;
}

watchEffect(() => {
  fetchHistory();
});

const sessionGapMs = 2 * 60 * 60 * 1000;

function buildSessionTitle(message: Api.Chat.Message) {
  const content = (message.content || '').replace(/\s+/g, ' ').trim();
  if (content) return content.slice(0, 18);
  const time = message.timestamp ? dayjs(message.timestamp).format('YYYY-MM-DD HH:mm') : '未命名对话';
  return `对话 ${time}`;
}

const sessions = computed<ChatSession[]>(() => {
  const sorted = [...historyMessages.value]
    .filter(item => Boolean(item.timestamp))
    .sort((a, b) => dayjs(a.timestamp).valueOf() - dayjs(b.timestamp).valueOf());

  const grouped: ChatSession[] = [];
  let current: ChatSession | null = null;
  let lastTime = 0;

  sorted.forEach(message => {
    const timeValue = dayjs(message.timestamp).valueOf();
    if (!current || timeValue - lastTime > sessionGapMs) {
      current = {
        id: `history-${grouped.length + 1}`,
        title: buildSessionTitle(message),
        timestamp: message.timestamp,
        messages: []
      };
      grouped.push(current);
    }

    current.messages.push(message);
    lastTime = timeValue;
  });

  return grouped.reverse();
});

const selectedSessionId = ref<'current' | string>('current');

const selectedMessages = computed(() => {
  if (selectedSessionId.value === 'current') return list.value;
  return sessions.value.find(session => session.id === selectedSessionId.value)?.messages || [];
});

const isHistoryView = computed(() => selectedSessionId.value !== 'current');

function handleNewChat() {
  selectedSessionId.value = 'current';
  list.value = [];
  input.value.message = '';
  chatStore.conversationId = '';
}

function handleSelectSession(id: string) {
  selectedSessionId.value = id;
}
</script>

<template>
  <div class="h-full flex gap-4">
    <aside class="w-260px shrink-0 flex-col gap-3 rounded-12px bg-white p-4 shadow-sm dark:bg-#1c1c1c">
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
      <div class="flex-col gap-2">
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
        :loading="isHistoryView ? historyLoading : false"
        :bind-scroll="!isHistoryView"
        :empty-text="isHistoryView ? '暂无历史对话内容' : '开始新的对话吧'"
      />
      <InputBox v-if="!isHistoryView" />
    </section>
  </div>
</template>

<style scoped></style>
