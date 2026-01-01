<script lang="ts" setup>
import {
  VEntity,
  VEntityField,
  VStatusDot,
  VDropdownItem,
} from "@halo-dev/components";
import type { LotteryActivity } from "@/api/generated";
import { computed } from "vue";
import { utils } from "@halo-dev/ui-shared";
import { lotteryConsoleApi } from "@/api";
import { Dialog, Toast } from "@halo-dev/components";

const props = withDefaults(
  defineProps<{
    lottery: LotteryActivity;
    isSelected?: boolean;
  }>(),
  {
    isSelected: false,
  }
);

const emit = defineEmits<{
  (event: "editing", lottery: LotteryActivity): void;
  (event: "delete", lottery: LotteryActivity): void;
}>();

const participationTypeMap: Record<string, string> = {
  NONE: "无条件",
  LOGIN: "需登录",
  COMMENT: "需评论",
  LOGIN_AND_COMMENT: "登录+评论",
};

const lotteryTypeMap: Record<string, string> = {
  SCHEDULED: "定时开奖",
  WHEEL: "大转盘",
  SCRATCH: "刮刮乐",
};

const stateConfig = computed(() => {
  const state = props.lottery.status?.state;
  switch (state) {
    case "RUNNING":
      return { state: "success" as const, text: "进行中", animate: true };
    case "DRAWN":
      return { state: "default" as const, text: "已开奖", animate: false };
    case "ENDED":
      return { state: "warning" as const, text: "已结束", animate: false };
    default:
      return { state: "default" as const, text: "待开始", animate: false };
  }
});

const handleDraw = async () => {
  Dialog.warning({
    title: "确定要开奖吗？",
    description: "开奖后无法撤销，请确认参与者已全部参与。",
    confirmText: "确定",
    cancelText: "取消",
    onConfirm: async () => {
      try {
        await lotteryConsoleApi.drawLottery({
          name: props.lottery.metadata?.name as string,
        });
        Toast.success("开奖成功");
      } catch (error) {
        console.error("Failed to draw lottery", error);
        Toast.error("开奖失败");
      }
    },
  });
};
</script>

<template>
  <VEntity :is-selected="isSelected">
    <template #checkbox>
      <slot name="checkbox" />
    </template>
    <template #start>
      <VEntityField
        :title="lottery.spec?.title || ''"
        :description="lottery.spec?.description || '暂无描述'"
        width="15rem"
      />
    </template>
    <template #end>
      <VEntityField>
        <template #description>
          <span class="text-xs text-gray-500">
            {{ lotteryTypeMap[lottery.spec?.lotteryType || "SCHEDULED"] }}
          </span>
        </template>
      </VEntityField>
      <VEntityField>
        <template #description>
          <span class="text-xs text-gray-500">
            {{ participationTypeMap[lottery.spec?.participationType || "NONE"] }}
          </span>
        </template>
      </VEntityField>
      <VEntityField>
        <template #description>
          <VStatusDot
            :state="stateConfig.state"
            :animate="stateConfig.animate"
            :text="stateConfig.text"
          />
        </template>
      </VEntityField>
      <VEntityField v-if="lottery.metadata?.deletionTimestamp">
        <template #description>
          <VStatusDot v-tooltip="'删除中'" state="warning" text="删除中" />
        </template>
      </VEntityField>
      <VEntityField>
        <template #description>
          <span class="text-xs text-gray-500">
            参与: {{ lottery.status?.participantCount || 0 }} 人
          </span>
        </template>
      </VEntityField>
      <VEntityField>
        <template #description>
          <span class="truncate text-xs tabular-nums text-gray-500">
            {{ utils.date.format(lottery.metadata?.creationTimestamp) }}
          </span>
        </template>
      </VEntityField>
    </template>
    <template #dropdownItems>
      <VDropdownItem @click="emit('editing', lottery)">编辑</VDropdownItem>
      <VDropdownItem
        v-if="lottery.status?.state === 'RUNNING' && lottery.spec?.lotteryType === 'SCHEDULED'"
        @click="handleDraw"
      >
        开奖
      </VDropdownItem>
      <VDropdownItem type="danger" @click="emit('delete', lottery)">
        删除
      </VDropdownItem>
    </template>
  </VEntity>
</template>
