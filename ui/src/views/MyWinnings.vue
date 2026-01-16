<script setup lang="ts">
import { ref, computed } from "vue";
import { useQuery } from "@tanstack/vue-query";
import {
  VPageHeader,
  VCard,
  VLoading,
  VEmpty,
  VPagination,
  IconRefreshLine,
  VEntityContainer,
  VEntity,
  VEntityField,
} from "@halo-dev/components";
import { lotteryUcApi, lotteryActivityApi } from "@/api";
import type { LotteryParticipant, LotteryActivity } from "@/api/generated";
import { utils } from "@halo-dev/ui-shared";
import RiGiftLine from "~icons/ri/gift-line";

const page = ref(1);
const size = ref(20);
const total = ref(0);

// 活动标题缓存
const activityTitleMap = ref<Record<string, string>>({});

interface WinningsListResult {
  page: number;
  size: number;
  total: number;
  items: LotteryParticipant[];
}

const {
  data: winnings,
  isLoading,
  isFetching,
  refetch,
} = useQuery({
  queryKey: ["my-winnings", page, size],
  queryFn: async (): Promise<WinningsListResult> => {
    const { data } = await lotteryUcApi.listMyWinnings({
      page: String(page.value),
      size: String(size.value),
    });
    total.value = data.total || 0;
    
    const items = (data.items || []) as LotteryParticipant[];
    
    // 获取缺少 activityTitle 的活动名称
    const missingActivityNames = items
      .filter(item => !item.spec?.activityTitle && item.spec?.activityName)
      .map(item => item.spec?.activityName as string)
      .filter(name => !activityTitleMap.value[name]);
    
    // 批量查询活动标题
    for (const name of [...new Set(missingActivityNames)]) {
      try {
        const { data: activity } = await lotteryActivityApi.getLotteryActivity({ name });
        if (activity?.spec?.title) {
          activityTitleMap.value[name] = activity.spec.title;
        }
      } catch {
        // 活动可能已删除
      }
    }
    
    return data as WinningsListResult;
  },
});

const winningItems = computed(() => winnings.value?.items || []);

// 获取活动标题（优先用 activityTitle，否则查缓存，最后用 activityName）
const getActivityTitle = (item: LotteryParticipant) => {
  return item.spec?.activityTitle 
    || activityTitleMap.value[item.spec?.activityName || ''] 
    || item.spec?.activityName;
};
</script>

<template>
  <VPageHeader title="我的中奖记录">
    <template #icon>
      <RiGiftLine class="mr-2 self-center" />
    </template>
  </VPageHeader>
  <div class="m-0 md:m-4">
    <VCard :body-class="['!p-0']">
      <template #header>
        <div class="block w-full bg-gray-50 px-4 py-3">
          <div class="flex items-center justify-between">
            <span class="text-sm text-gray-600">共 {{ total }} 条中奖记录</span>
            <div
              class="group cursor-pointer rounded p-1 hover:bg-gray-200"
              @click="refetch()"
            >
              <IconRefreshLine
                v-tooltip="'刷新'"
                :class="{ 'animate-spin text-gray-900': isFetching }"
                class="h-4 w-4 text-gray-600 group-hover:text-gray-900"
              />
            </div>
          </div>
        </div>
      </template>
      <VLoading v-if="isLoading" />
      <Transition v-else-if="!winningItems.length" appear name="fade">
        <VEmpty message="参与更多抽奖活动，好运就在前方！" title="暂无中奖记录" />
      </Transition>
      <Transition v-else appear name="fade">
        <VEntityContainer>
          <VEntity v-for="item in winningItems" :key="item.metadata?.name">
            <template #start>
              <VEntityField>
                <template #description>
                  <div class="flex items-center gap-2">
                    <span class="rounded bg-amber-100 px-2 py-0.5 text-sm font-medium text-amber-700">
                      🎁 {{ item.spec?.prizeName }}
                    </span>
                  </div>
                </template>
              </VEntityField>
            </template>
            <template #end>
              <VEntityField>
                <template #description>
                  <span class="text-xs text-gray-500">
                    活动: {{ getActivityTitle(item) }}
                  </span>
                </template>
              </VEntityField>
              <VEntityField>
                <template #description>
                  <span class="truncate text-xs tabular-nums text-gray-500">
                    {{ utils.date.format(item.spec?.winTime) }}
                  </span>
                </template>
              </VEntityField>
            </template>
          </VEntity>
        </VEntityContainer>
      </Transition>
      <template #footer>
        <VPagination
          v-model:page="page"
          v-model:size="size"
          page-label="页"
          size-label="条 / 页"
          :total-label="`共 ${total} 项数据`"
          :total="total"
          :size-options="[20, 30, 50]"
        />
      </template>
    </VCard>
  </div>
</template>
