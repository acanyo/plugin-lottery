<script setup lang="ts">
import { useRouteQuery } from "@vueuse/router";
import { computed, ref, watch } from "vue";
import { useQuery, useQueryClient } from "@tanstack/vue-query";
import {
  IconAddCircle,
  VPageHeader,
  VCard,
  VLoading,
  VEmpty,
  VSpace,
  VButton,
  VPagination,
  IconRefreshLine,
  Dialog,
  Toast,
  VEntityContainer,
} from "@halo-dev/components";
import { lotteryActivityApi, lotteryConsoleApi } from "@/api";
import LotteryListItem from "@/components/LotteryListItem.vue";
import type { LotteryActivity } from "@/api/generated";
import LotteryEditingModal from "@/components/LotteryEditingModal.vue";
import RiGiftLine from "~icons/ri/gift-line";

const queryClient = useQueryClient();

const editingModal = ref(false);
const checkAll = ref(false);
const selectedLottery = ref<LotteryActivity>();
const selectedLotteryNames = ref<string[]>([]);

const page = useRouteQuery<number>("page", 1, {
  transform: Number,
});
const size = useRouteQuery<number>("size", 20, {
  transform: Number,
});
const selectedSort = useRouteQuery<string | undefined>("sort");
const selectedState = useRouteQuery<string | undefined>("state");
const selectedParticipationType = useRouteQuery<string | undefined>("participationType");
const keyword = useRouteQuery<string>("keyword", "");
const total = ref(0);

watch(
  () => [selectedState.value, selectedSort.value, selectedParticipationType.value, keyword.value],
  () => {
    page.value = 1;
  }
);

const handleClearFilters = () => {
  selectedState.value = undefined;
  selectedSort.value = undefined;
  selectedParticipationType.value = undefined;
};

const hasFilters = computed(() => {
  return selectedState.value || selectedSort.value || selectedParticipationType.value;
});

interface LotteryListResult {
  page: number;
  size: number;
  total: number;
  items: LotteryActivity[];
}

const {
  data: lotteries,
  isLoading,
  isFetching,
  refetch,
} = useQuery({
  queryKey: ["lotteries", page, size, keyword, selectedSort, selectedState, selectedParticipationType],
  queryFn: async (): Promise<LotteryListResult> => {
    const { data } = await lotteryConsoleApi.listLotteries({
      page: page.value,
      size: size.value,
      keyword: keyword.value || undefined,
      state: selectedState.value || undefined,
      participationType: selectedParticipationType.value || undefined,
      sort: selectedSort.value ? [selectedSort.value] : undefined,
    });
    total.value = data.total || 0;
    return data as LotteryListResult;
  },
  refetchInterval: (query) => {
    const items = query.state.data?.items;
    const hasDeletingLottery = items?.some(
      (lottery: LotteryActivity) => lottery?.metadata?.deletionTimestamp
    );
    return hasDeletingLottery ? 1000 : false;
  },
});

const lotteryItems = computed(() => lotteries.value?.items);

const handleOpenEditingModal = (lottery?: LotteryActivity) => {
  selectedLottery.value = lottery;
  editingModal.value = true;
};

const handleCheckAllChange = (e: Event) => {
  const { checked } = e.target as HTMLInputElement;
  if (checked) {
    selectedLotteryNames.value =
      lotteryItems.value?.map((lottery) => lottery.metadata?.name || "") || [];
  } else {
    selectedLotteryNames.value = [];
  }
};

const checkSelection = (lottery: LotteryActivity) => {
  return (
    lottery.metadata?.name === selectedLottery.value?.metadata?.name ||
    selectedLotteryNames.value.includes(lottery.metadata?.name || "")
  );
};

watch(
  () => selectedLotteryNames.value,
  (newValue) => {
    checkAll.value = newValue.length === lotteryItems.value?.length;
  }
);

const handleDeleteInBatch = async () => {
  Dialog.warning({
    title: "确定要删除选中的抽奖活动吗？",
    description: "该操作不可恢复。",
    confirmType: "danger",
    confirmText: "确定",
    cancelText: "取消",
    onConfirm: async () => {
      try {
        const promises = selectedLotteryNames.value.map((name) => {
          return lotteryActivityApi.deleteLotteryActivity({ name });
        });
        await Promise.all(promises);
        selectedLotteryNames.value = [];
        Toast.success("删除成功");
      } catch (e) {
        console.error("Failed to delete lottery", e);
      } finally {
        refetch();
      }
    },
  });
};

const handleDelete = async (lottery?: LotteryActivity) => {
  Dialog.warning({
    title: "确定要删除该抽奖活动吗？",
    description: "删除之后将无法恢复。",
    confirmType: "danger",
    confirmText: "确定",
    cancelText: "取消",
    onConfirm: async () => {
      try {
        await lotteryActivityApi.deleteLotteryActivity({
          name: lottery?.metadata?.name as string,
        });
        Toast.success("删除成功");
      } catch (error) {
        console.error("Failed to delete lottery", error);
      } finally {
        queryClient.invalidateQueries({ queryKey: ["lotteries"] });
      }
    },
  });
};

const onEditingModalClose = () => {
  selectedLottery.value = undefined;
  editingModal.value = false;
  refetch();
};
</script>

<template>
  <LotteryEditingModal
    v-if="editingModal"
    :lottery="selectedLottery"
    @close="onEditingModalClose"
  />
  <VPageHeader title="抽奖管理">
    <template #icon>
      <RiGiftLine class="mr-2 self-center" />
    </template>
    <template #actions>
      <VButton
        v-permission="['plugin:lottery:manage']"
        type="secondary"
        @click="editingModal = true"
      >
        <template #icon>
          <IconAddCircle class="h-full w-full" />
        </template>
        新建
      </VButton>
    </template>
  </VPageHeader>
  <div class="m-0 md:m-4">
    <VCard :body-class="['!p-0']">
      <template #header>
        <div class="block w-full bg-gray-50 px-4 py-3">
          <div
            class="relative flex flex-col flex-wrap items-start gap-4 sm:flex-row sm:items-center"
          >
            <div
              v-permission="['plugin:lottery:manage']"
              class="hidden items-center sm:flex"
            >
              <input
                v-model="checkAll"
                type="checkbox"
                @change="handleCheckAllChange"
              />
            </div>
            <div class="flex w-full flex-1 items-center sm:w-auto">
              <SearchInput v-if="!selectedLotteryNames.length" v-model="keyword" />
              <VSpace v-else>
                <VButton type="danger" @click="handleDeleteInBatch">
                  删除
                </VButton>
              </VSpace>
            </div>
            <VSpace spacing="lg" class="flex-wrap">
              <FilterCleanButton v-if="hasFilters" @click="handleClearFilters" />
              <FilterDropdown
                v-model="selectedState"
                label="状态"
                :items="[
                  { label: '全部', value: undefined },
                  { label: '待开始', value: 'PENDING' },
                  { label: '进行中', value: 'RUNNING' },
                  { label: '已结束', value: 'ENDED' },
                  { label: '已开奖', value: 'DRAWN' },
                ]"
              />
              <FilterDropdown
                v-model="selectedParticipationType"
                label="参与类型"
                :items="[
                  { label: '全部', value: undefined },
                  { label: '无条件', value: 'NONE' },
                  { label: '需登录', value: 'LOGIN' },
                  { label: '需评论', value: 'COMMENT' },
                  { label: '登录+评论', value: 'LOGIN_AND_COMMENT' },
                ]"
              />
              <FilterDropdown
                v-model="selectedSort"
                label="排序"
                :items="[
                  { label: '默认' },
                  { label: '较近创建', value: 'metadata.creationTimestamp,desc' },
                  { label: '较早创建', value: 'metadata.creationTimestamp,asc' },
                ]"
              />
              <div class="flex flex-row gap-2">
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
            </VSpace>
          </div>
        </div>
      </template>
      <VLoading v-if="isLoading" />
      <Transition v-else-if="!lotteryItems?.length" appear name="fade">
        <VEmpty message="你可以尝试刷新或者新建抽奖活动" title="当前没有抽奖活动">
          <template #actions>
            <VSpace>
              <VButton @click="refetch"> 刷新 </VButton>
              <VButton
                v-permission="['plugin:lottery:manage']"
                type="secondary"
                @click="editingModal = true"
              >
                <template #icon>
                  <IconAddCircle class="h-full w-full" />
                </template>
                新建
              </VButton>
            </VSpace>
          </template>
        </VEmpty>
      </Transition>
      <Transition v-else appear name="fade">
        <VEntityContainer>
          <LotteryListItem
            v-for="lottery in lotteryItems"
            :key="lottery.metadata?.name"
            :lottery="lottery"
            :is-selected="checkSelection(lottery)"
            @editing="handleOpenEditingModal"
            @delete="handleDelete"
          >
            <template #checkbox>
              <input
                v-model="selectedLotteryNames"
                :value="lottery.metadata?.name"
                type="checkbox"
              />
            </template>
          </LotteryListItem>
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
          :size-options="[20, 30, 50, 100]"
        />
      </template>
    </VCard>
  </div>
</template>
