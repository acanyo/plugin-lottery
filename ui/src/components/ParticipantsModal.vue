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

const fetchParticipants = async () => {
  if (!props.lottery?.metadata?.name) return;
  
  loading.value = true;
  try {
    const { data } = await lotteryParticipantApi.listLotteryParticipant({
      fieldSelector: [`spec.activityName=${props.lottery.metadata.name}`],
    });
    participants.value = data.items || [];
  } catch (error) {
    console.error("Failed to fetch participants", error);
  } finally {
    loading.value = false;
  }
};

watch(
  () => props.visible,
  (val) => {
    if (val) {
      fetchParticipants();
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
    :width="650"
    title="参与人列表"
    @update:visible="emit('update:visible', $event)"
    @close="handleClose"
  >
    <div class="participants-modal">
      <div v-if="loading" class="flex justify-center py-8">
        <VLoading />
      </div>
      <VEmpty
        v-else-if="!participants.length"
        title="暂无参与记录"
        message="该活动还没有人参与"
      />
      <div v-else class="space-y-2">
        <div class="mb-3 text-sm text-gray-500">
          共 {{ participants.length }} 人参与
        </div>
        <div
          v-for="participant in participants"
          :key="participant.metadata?.name"
          class="flex items-center justify-between rounded-lg border p-3"
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
              <span
                v-if="participant.spec?.isWinner"
                class="rounded bg-amber-100 px-1.5 py-0.5 text-xs font-medium text-amber-700"
              >
                🎁 {{ participant.spec?.prizeName }}
              </span>
            </div>
            <span class="text-xs text-gray-500">
              {{ participant.spec?.email }}
            </span>
          </div>
          <div class="text-right">
            <span class="text-xs text-gray-400">
              {{ utils.date.format(participant.spec?.participateTime) }}
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
.participants-modal {
  max-height: 400px;
  overflow-y: auto;
}
</style>
