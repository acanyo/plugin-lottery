<script lang="ts" setup>
import { VModal, VButton, VEmpty, VLoading } from "@halo-dev/components";
import type { LotteryActivity, LotteryParticipant } from "@/api/generated";
import { ref, watch } from "vue";
import { lotteryParticipantApi } from "@/api";
import { utils } from "@halo-dev/ui-shared";

const props = defineProps<{
  visible: boolean;
  lottery?: LotteryActivity;
}>();

const emit = defineEmits<{
  (event: "update:visible", value: boolean): void;
  (event: "close"): void;
}>();

const loading = ref(false);
const participants = ref<LotteryParticipant[]>([]);

// 获取中奖记录
const fetchWinners = async () => {
  if (!props.lottery?.metadata?.name) return;
  
  loading.value = true;
  try {
    const { data } = await lotteryParticipantApi.listLotteryParticipant({
      fieldSelector: [`spec.activityName=${props.lottery.metadata.name}`, `spec.isWinner=true`],
    });
    participants.value = data.items || [];
  } catch (error) {
    console.error("Failed to fetch winners", error);
  } finally {
    loading.value = false;
  }
};

watch(
  () => props.visible,
  (val) => {
    if (val) {
      fetchWinners();
    }
  }
);

const handleClose = () => {
  emit("update:visible", false);
  emit("close");
};
</script>

<template>
  <VModal
    :visible="visible"
    :width="600"
    title="中奖记录"
    @update:visible="emit('update:visible', $event)"
    @close="handleClose"
  >
    <div class="winners-modal">
      <div v-if="loading" class="flex justify-center py-8">
        <VLoading />
      </div>
      <VEmpty
        v-else-if="!participants.length"
        title="暂无中奖记录"
        message="该活动还没有中奖者"
      />
      <div v-else class="space-y-3">
        <div
          v-for="participant in participants"
          :key="participant.metadata?.name"
          class="flex items-center justify-between rounded-lg border p-4"
        >
          <div class="flex flex-col gap-1">
            <div class="flex items-center gap-2">
              <span class="font-medium">
                {{ participant.spec?.displayName || participant.spec?.email }}
              </span>
              <span
                v-if="participant.spec?.username"
                class="text-xs text-gray-400"
              >
                @{{ participant.spec?.username }}
              </span>
            </div>
            <span class="text-xs text-gray-500">
              {{ participant.spec?.email }}
            </span>
          </div>
          <div class="flex flex-col items-end gap-1">
            <span class="rounded bg-amber-100 px-2 py-0.5 text-sm font-medium text-amber-700">
              🎁 {{ participant.spec?.prizeName }}
            </span>
            <span class="text-xs text-gray-400">
              {{ utils.date.format(participant.spec?.winTime) }}
            </span>
          </div>
        </div>
      </div>
    </div>
    <template #footer>
      <VButton @click="handleClose">关闭</VButton>
    </template>
  </VModal>
</template>

<style scoped>
.winners-modal {
  max-height: 400px;
  overflow-y: auto;
}
</style>
