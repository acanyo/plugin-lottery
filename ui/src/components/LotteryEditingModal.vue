<script lang="ts" setup>
import { computed, ref, watch } from "vue";
import { Toast, VAlert, VButton, VModal, VSpace } from "@halo-dev/components";
import { cloneDeep } from "lodash-es";
import type { LotteryActivity } from "@/api/generated";
import { lotteryActivityApi } from "@/api";
import { submitForm } from "@formkit/core";
import { utils } from "@halo-dev/ui-shared";

const props = withDefaults(
  defineProps<{
    lottery?: LotteryActivity;
  }>(),
  {
    lottery: undefined,
  }
);

const emit = defineEmits<{
  (event: "close"): void;
}>();

const formState = ref<LotteryActivity>({
  spec: {
    title: "",
    description: "",
    lotteryType: "SCHEDULED",
    participationType: "NONE",
    targetPostName: "",
    startTime: undefined,
    endTime: undefined,
    drawTime: undefined,
    maxParticipants: undefined,
    allowDuplicate: false,
    thankYouSlots: 2,
    prizes: [],
  },
  apiVersion: "lottery.xhhao.com/v1alpha1",
  kind: "LotteryActivity",
  metadata: {
    generateName: "lottery-",
    name: "",
  },
  status: {
    state: "PENDING",
    participantCount: 0,
  },
});

const modal = ref<InstanceType<typeof VModal> | null>(null);
const saving = ref(false);

const isUpdateMode = computed(() => !!props.lottery);

const modalTitle = computed(() => {
  return isUpdateMode.value ? "编辑抽奖活动" : "新建抽奖活动";
});

const handleSaveLottery = async () => {
  // 校验奖品
  if (!formState.value.spec?.prizes?.length) {
    Toast.error("请至少添加一个奖品");
    return;
  }

  // 校验概率总和
  if (totalProbability.value > 100) {
    Toast.error("概率总和不能超过 100%");
    return;
  }

  // 处理时间格式
  if (formState.value.spec?.startTime) {
    formState.value.spec.startTime = utils.date.toISOString(
      formState.value.spec.startTime
    );
  }
  if (formState.value.spec?.endTime) {
    formState.value.spec.endTime = utils.date.toISOString(
      formState.value.spec.endTime
    );
  }
  if (formState.value.spec?.drawTime) {
    formState.value.spec.drawTime = utils.date.toISOString(
      formState.value.spec.drawTime
    );
  }

  try {
    saving.value = true;
    if (isUpdateMode.value) {
      await lotteryActivityApi.updateLotteryActivity({
        name: formState.value.metadata?.name || "",
        lotteryActivity: formState.value,
      });
    } else {
      await lotteryActivityApi.createLotteryActivity({
        lotteryActivity: formState.value,
      });
    }
    modal.value?.close();
    Toast.success("保存成功");
  } catch (error) {
    console.error("Failed to save lottery", error);
    Toast.error("保存失败");
  } finally {
    saving.value = false;
  }
};

const handleSubmit = () => {
  submitForm("lottery-form");
};

watch(
  () => props.lottery,
  (lottery) => {
    if (lottery) {
      formState.value = cloneDeep(lottery);
      // 转换时间格式用于表单显示
      if (formState.value.spec?.startTime) {
        formState.value.spec.startTime = utils.date.toDatetimeLocal(
          formState.value.spec.startTime
        );
      }
      if (formState.value.spec?.endTime) {
        formState.value.spec.endTime = utils.date.toDatetimeLocal(
          formState.value.spec.endTime
        );
      }
      if (formState.value.spec?.drawTime) {
        formState.value.spec.drawTime = utils.date.toDatetimeLocal(
          formState.value.spec.drawTime
        );
      }
    }
  },
  { immediate: true }
);

const needsTargetPost = computed(() => {
  return (
    formState.value.spec?.participationType === "COMMENT" ||
    formState.value.spec?.participationType === "LOGIN_AND_COMMENT"
  );
});

const isInstantLottery = computed(() => {
  return (
    formState.value.spec?.lotteryType === "WHEEL" ||
    formState.value.spec?.lotteryType === "DRAW"
  );
});

// 计算概率总和
const totalProbability = computed(() => {
  const prizes = formState.value.spec?.prizes || [];
  return prizes.reduce((sum, prize) => sum + (Number(prize.probability) || 0), 0);
});

// 概率是否超过100%
const isProbabilityExceeded = computed(() => totalProbability.value > 100);

// 谢谢参与概率
const noPrizeProbability = computed(() => Math.max(0, 100 - totalProbability.value));
</script>

<template>
  <VModal ref="modal" :title="modalTitle" :width="600" @close="emit('close')">
    <FormKit
      id="lottery-form"
      type="form"
      name="lottery-form"
      :config="{ validationVisibility: 'submit' }"
      @submit="handleSaveLottery"
    >
      <!-- 基本信息 -->
      <FormKit
        v-model="formState.spec!.title"
        name="title"
        label="活动标题"
        type="text"
        validation="required|length:0,50"
        placeholder="请输入活动标题"
      />
      <FormKit
        v-model="formState.spec!.description"
        name="description"
        label="活动描述"
        type="textarea"
        validation="length:0,200"
        placeholder="请输入活动描述"
      />
      <FormKit
        v-model="formState.spec!.lotteryType"
        :options="[
          { label: '定时开奖', value: 'SCHEDULED' },
          { label: '大转盘（即时开奖）', value: 'WHEEL' },
          { label: '抽签（即时开奖）', value: 'DRAW' },
        ]"
        label="抽奖类型"
        type="radio"
        name="lotteryType"
        help="定时开奖：到时间统一抽取；大转盘/抽签：参与即抽，立即出结果"
      />
      <FormKit
        v-model="formState.spec!.participationType"
        :options="[
          { label: '无条件（需邮箱）', value: 'NONE' },
          { label: '需登录', value: 'LOGIN' },
          { label: '需评论', value: 'COMMENT' },
          { label: '登录+评论', value: 'LOGIN_AND_COMMENT' },
        ]"
        label="参与条件"
        type="radio"
        name="participationType"
      />
      <FormKit
        v-if="needsTargetPost"
        v-model="formState.spec!.targetPostName"
        name="targetPostName"
        label="关联文章"
        type="text"
        placeholder="请输入文章的 name"
        validation="required"
      />

      <!-- 时间设置 -->
      <div class="formkit-outer">
        <VAlert
          type="info"
          title="时间说明"
          description="开始时间到结束时间为参与时间窗口。开奖时间留空则在结束时间自动开奖，也可设置晚于结束时间延迟开奖。"
          :closable="false"
        />
      </div>
      <FormKit
        v-model="formState.spec!.startTime"
        name="startTime"
        label="开始时间"
        type="datetime-local"
      />
      <FormKit
        v-model="formState.spec!.endTime"
        name="endTime"
        label="结束时间"
        type="datetime-local"
      />
      <FormKit
        v-model="formState.spec!.drawTime"
        name="drawTime"
        label="开奖时间"
        type="datetime-local"
        help="留空则在结束时间自动开奖，设置后可延迟开奖"
      />

      <!-- 其他设置 -->
      <FormKit
        v-model="formState.spec!.maxParticipants"
        name="maxParticipants"
        label="最大参与人数"
        type="number"
        placeholder="留空则不限制"
      />
      <FormKit
        v-model="formState.spec!.allowDuplicate"
        name="allowDuplicate"
        label="允许重复参与"
        type="checkbox"
      />

      <!-- 即时开奖设置 -->
      <FormKit
        v-if="isInstantLottery"
        v-model="formState.spec!.thankYouSlots"
        name="thankYouSlots"
        label="谢谢参与格子数"
        type="number"
        value="2"
        min="1"
        max="6"
        help="大转盘/刮刮乐中显示的'谢谢参与'格子数量，与奖品交替排列"
      />

      <!-- 奖品设置 -->
      <div class="formkit-outer">
        <div class="formkit-label">奖品设置</div>
        <div
          class="mb-3 rounded-md p-3 text-sm"
          :class="isProbabilityExceeded ? 'bg-red-50 text-red-600' : 'bg-gray-50 text-gray-600'"
        >
          <div class="flex items-center justify-between">
            <span>已设置概率总和：</span>
            <span class="font-medium" :class="isProbabilityExceeded ? 'text-red-600' : ''">
              {{ totalProbability }}%
            </span>
          </div>
          <div class="flex items-center justify-between mt-1">
            <span>谢谢参与概率：</span>
            <span class="font-medium">{{ noPrizeProbability }}%</span>
          </div>
          <div v-if="isProbabilityExceeded" class="mt-2 text-red-600 font-medium">
            ⚠️ 概率总和超过 100%，请调整各奖品概率
          </div>
        </div>
      </div>
      <FormKit
        v-model="formState.spec!.prizes"
        type="array"
        name="prizes"
        add-label="添加奖品"
        empty-text="暂无奖品，请点击下方按钮添加"
        :item-labels="[
          { type: 'image', label: '$value.imageUrl' },
          { type: 'text', label: '$value.name' },
          { type: 'text', label: 'x$value.quantity' },
        ]"
      >
        <FormKit
          type="text"
          name="name"
          label="奖品名称"
          validation="required"
          placeholder="奖品名称"
        />
        <FormKit
          type="text"
          name="description"
          label="奖品描述"
          placeholder="奖品描述"
        />
        <FormKit
          type="number"
          name="quantity"
          label="数量"
          value="1"
          min="1"
          validation="required"
        />
        <FormKit
          type="number"
          name="probability"
          label="中奖概率 (%)"
          value="10"
          min="0"
          max="100"
          validation="required"
          help="0-100，所有奖品概率之和为总中奖率，剩余为谢谢参与"
        />
        <FormKit
          type="attachment"
          name="imageUrl"
          label="奖品图片"
          :accepts="['image/*']"
          placeholder="选择或上传图片"
        />
      </FormKit>
    </FormKit>
    <template #footer>
      <div class="flex justify-between">
        <VSpace>
          <VButton
            :loading="saving"
            :disabled="saving"
            type="secondary"
            @click="handleSubmit"
          >
            提交
          </VButton>
        </VSpace>
        <VButton @click="modal?.close()"> 关闭 </VButton>
      </div>
    </template>
  </VModal>
</template>
