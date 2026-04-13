<script lang="ts" setup>
import { computed, onMounted, ref, watch } from "vue";
import { Toast, VAlert, VButton, VModal, VSpace } from "@halo-dev/components";
import { consoleApiClient } from "@halo-dev/api-client";
import { cloneDeep } from "lodash-es";
import type { LotteryActivity, RedisConfigStatus } from "@/api/generated";
import { lotteryActivityApi, lotteryConsoleApi } from "@/api";
import { submitForm } from "@formkit/core";
import { utils } from "@halo-dev/ui-shared";

interface ManualAssignment {
  prizeName?: string;
  participantToken?: string;
  participantIdentifier?: string;
  participantDisplayName?: string;
}

interface PrizeManualWinner {
  candidateKey?: string;
}

interface EditablePrize {
  name?: string;
  description?: string;
  imageUrl?: string;
  quantity?: number;
  remaining?: number;
  probability?: number;
  manualWinners?: PrizeManualWinner[];
}

interface ManualWinnerCandidate {
  key: string;
  identifier: string;
  displayName: string;
  username?: string;
  email?: string;
}

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
const redisStatus = ref<RedisConfigStatus>();
const users = ref<ManualWinnerCandidate[]>([]);
const loadingUsers = ref(false);

const isUpdateMode = computed(() => !!props.lottery);

const modalTitle = computed(() => {
  return isUpdateMode.value ? "编辑抽奖活动" : "新建抽奖活动";
});

const lotteryTypeOptions = computed(() => {
  const instantOptionDisabled = !instantLotteryAvailable.value;
  return [
    { label: "定时开奖", value: "SCHEDULED" },
    {
      label: instantOptionDisabled
        ? "大转盘（即时开奖，需先通过 Redis 测试）"
        : "大转盘（即时开奖）",
      value: "WHEEL",
      disabled: instantOptionDisabled,
      attrs: {
        disabled: instantOptionDisabled,
      },
    },
    {
      label: instantOptionDisabled
        ? "抽签（即时开奖，需先通过 Redis 测试）"
        : "抽签（即时开奖）",
      value: "DRAW",
      disabled: instantOptionDisabled,
      attrs: {
        disabled: instantOptionDisabled,
      },
    },
  ];
});

const instantLotteryAvailable = computed(() => {
  return !!redisStatus.value?.instantLotteryAvailable;
});

const effectiveRedisSourceText = computed(() => {
  switch (redisStatus.value?.effectiveSource) {
    case "PLUGIN":
      return "插件配置";
    case "HALO":
      return "Halo 全局配置";
    default:
      return "未启用";
  }
});

const effectiveRedisEndpoint = computed(() => {
  const config = redisStatus.value?.effectiveConfig;
  if (!config?.host) {
    return "未检测到可用连接";
  }
  return `${config.host}:${config.port}`;
});

const instantLotteryAlertType = computed(() => {
  return instantLotteryAvailable.value ? "success" : "warning";
});

const instantLotteryAlertDescription = computed(() => {
  if (instantLotteryAvailable.value) {
    return `当前使用${effectiveRedisSourceText.value}（${effectiveRedisEndpoint.value}），即时开奖会通过 Redis 原子扣减奖品库存。`;
  }

  return "当前未检测到可用 Redis，不能保存为即时开奖。请先在 Redis 设置页完成配置，或改用定时开奖。";
});

const fetchRedisStatus = async () => {
  try {
    const { data } = await lotteryConsoleApi.getLotteryRedisConfig();
    redisStatus.value = data;
  } catch (error) {
    console.error("Failed to fetch redis status", error);
  }
};

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

  if (isInstantLottery.value && !instantLotteryAvailable.value) {
    Toast.error(
      "当前未检测到可用 Redis，不能保存为即时开奖，请先配置 Redis 或改用定时开奖"
    );
    return;
  }

  const payload = cloneDeep(formState.value) as LotteryActivity & {
    spec: LotteryActivity["spec"] & {
      manualAssignments?: ManualAssignment[];
      prizes?: EditablePrize[];
    };
  };

  try {
    if (manualAssignmentEditable.value) {
      validateManualAssignments(payload.spec.prizes || []);
      payload.spec.manualAssignments = buildManualAssignments(payload.spec.prizes || []);
    } else {
      payload.spec.manualAssignments = [];
    }
    payload.spec.prizes = sanitizePrizes(payload.spec.prizes || []) as EditablePrize[];
  } catch (error) {
    Toast.error(error instanceof Error ? error.message : "指定中奖人校验失败");
    return;
  }

  // 处理时间格式
  if (payload.spec?.startTime) {
    payload.spec.startTime = utils.date.toISOString(payload.spec.startTime);
  }
  if (payload.spec?.endTime) {
    payload.spec.endTime = utils.date.toISOString(payload.spec.endTime);
  }
  if (payload.spec?.drawTime) {
    payload.spec.drawTime = utils.date.toISOString(payload.spec.drawTime);
  }

  try {
    saving.value = true;
    if (isUpdateMode.value) {
      await lotteryActivityApi.updateLotteryActivity({
        name: payload.metadata?.name || "",
        lotteryActivity: payload,
      });
    } else {
      await lotteryActivityApi.createLotteryActivity({
        lotteryActivity: payload,
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
      hydratePrizeManualWinners(lottery);
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
      return;
    }

    formState.value = {
      spec: {
        title: "",
        description: "",
        lotteryType: "SCHEDULED",
        participationType: "NONE",
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
    };
  },
  { immediate: true }
);

watch(
  () => formState.value.spec?.lotteryType,
  (lotteryType, previousLotteryType) => {
    const switchedToInstant =
      lotteryType === "WHEEL" || lotteryType === "DRAW";
    const previousWasInstant =
      previousLotteryType === "WHEEL" || previousLotteryType === "DRAW";

    if (switchedToInstant && !previousWasInstant && !instantLotteryAvailable.value) {
      Toast.warning("当前未检测到可用 Redis，即时开奖暂不可保存");
    }
  }
);

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

const manualAssignmentEditable = computed(() => {
  return formState.value.spec?.lotteryType === "SCHEDULED";
});

const manualWinnerCandidateMap = computed(() => {
  return new Map(users.value.map((candidate) => [candidate.key, candidate]));
});

const hydratePrizeManualWinners = (lottery?: LotteryActivity) => {
  const groupedAssignments = new Map<string, PrizeManualWinner[]>();
  const assignments = ((lottery?.spec as LotteryActivity["spec"] & {
    manualAssignments?: ManualAssignment[];
  })?.manualAssignments || []) as ManualAssignment[];

  assignments.forEach((assignment) => {
    if (!assignment?.prizeName) {
      return;
    }
    const candidateKey = assignment.participantIdentifier || "";
    const current = groupedAssignments.get(assignment.prizeName) || [];
    current.push({ candidateKey });
    groupedAssignments.set(assignment.prizeName, current);
  });

  ((formState.value.spec?.prizes || []) as EditablePrize[]).forEach((prize) => {
    prize.manualWinners = groupedAssignments.get(prize.name || "") || [];
  });
};

const sanitizePrizes = (prizes: EditablePrize[]) => {
  return prizes.map(({ manualWinners: _manualWinners, ...prize }) => prize);
};

const buildManualAssignments = (prizes: EditablePrize[]): ManualAssignment[] => {
  return prizes.flatMap((prize) =>
    (prize.manualWinners || [])
      .map((manualWinner) => manualWinnerCandidateMap.value.get(manualWinner.candidateKey || ""))
      .filter((candidate): candidate is ManualWinnerCandidate => !!candidate)
      .map((candidate) => ({
        prizeName: prize.name,
        participantIdentifier: candidate.identifier,
        participantDisplayName: candidate.displayName,
      }))
  );
};

const validateManualAssignments = (prizes: EditablePrize[]) => {
  const occupiedCandidates = new Set<string>();

  for (const prize of prizes) {
    const manualWinners = (prize.manualWinners || []).filter(
      (winner) => winner.candidateKey
    );
    const quantity = Math.max(prize.quantity || 0, 0);

    if (manualWinners.length > quantity) {
      throw new Error(`奖品 ${prize.name || "未命名奖品"} 指定人数不能超过奖品数量`);
    }

    for (const manualWinner of manualWinners) {
      const candidateKey = manualWinner.candidateKey || "";
      if (occupiedCandidates.has(candidateKey)) {
        throw new Error("同一个用户不能被多个奖品重复指定");
      }
      occupiedCandidates.add(candidateKey);
    }
  }
};

const fetchUsers = async () => {
  loadingUsers.value = true;
  try {
    const { data } = await consoleApiClient.user.listUsers({
      page: 1,
      size: 5000,
      sort: ["metadata.creationTimestamp,desc"],
      fieldSelector: [
        "name!=anonymousUser",
        "name!=ghost",
        "spec.disabled!=true",
      ],
    });

    users.value = (data.items || []).reduce<ManualWinnerCandidate[]>(
      (result, item) => {
        const username = item.user.metadata.name;
        const email = item.user.spec.email;
        const identifier = username || email;
        if (!identifier || item.user.spec.disabled) {
          return result;
        }
        result.push({
          key: username,
          identifier,
          displayName: item.user.spec.displayName || identifier,
          username,
          email,
        });
        return result;
      },
      []
    );
  } catch (error) {
    console.error("Failed to fetch users", error);
  } finally {
    loadingUsers.value = false;
  }
};

onMounted(() => {
  fetchRedisStatus();
  fetchUsers();
});
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
        :options="lotteryTypeOptions"
        label="抽奖类型"
        type="radio"
        name="lotteryType"
        help="定时开奖：到时间统一抽取；大转盘/抽签：参与即抽，立即出结果"
      />
      <div v-if="isInstantLottery" class="formkit-outer">
        <VAlert
          :type="instantLotteryAlertType"
          title="即时开奖依赖 Redis"
          :description="instantLotteryAlertDescription"
          :closable="false"
        />
      </div>
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
        help="评论参与会验证用户在抽奖卡片所在文章下的评论"
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
        <VAlert
          v-if="formState.spec?.lotteryType === 'SCHEDULED'"
          type="info"
          title="可在下方为每个奖品指定中奖人"
          description="定时开奖支持按奖品数量指定中奖人。未指定的名额会在开奖时继续走随机抽取。"
          :closable="false"
          class="mb-3"
        />
        <div
          v-if="isInstantLottery"
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
        <VAlert
          v-else-if="formState.spec?.lotteryType === 'SCHEDULED'"
          type="info"
          title="定时开奖按奖品数量抽取"
          description="手动开奖和自动开奖都会按奖品数量从参与者中随机抽取，中奖概率配置不会参与计算。"
          :closable="false"
          class="mb-3"
        />
      </div>
      <FormKit
        v-model="formState.spec!.prizes"
        type="array"
        name="prizes"
        add-label="添加奖品"
        empty-text="暂无奖品，请点击下方按钮添加"
        :item-labels="[
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
          v-if="isInstantLottery"
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
        <div
          v-if="formState.spec?.lotteryType === 'SCHEDULED'"
          class="rounded-xl border border-orange-200 bg-orange-50 p-4"
        >
          <div class="mb-2 text-sm font-medium text-orange-900">指定中奖人</div>
          <p class="mb-3 text-xs leading-6 text-orange-700">
            可直接从后台用户里指定。未参与的用户如果被指定，开奖时会自动补录为参与者。
          </p>
          <VAlert
            v-if="loadingUsers"
            type="info"
            title="正在加载用户列表"
            description="稍候即可为当前奖品指定中奖人。"
            :closable="false"
            class="mb-3"
          />
          <VAlert
            v-else-if="!users.length"
            type="warning"
            title="暂无可选用户"
            description="当前没有可指定的后台用户。"
            :closable="false"
            class="mb-3"
          />
          <FormKit
            v-else
            type="array"
            name="manualWinners"
            add-label="添加指定中奖人"
            empty-text="未指定中奖人，开奖时将随机抽取"
          >
            <FormKit
              type="userSelect"
              name="candidateKey"
              label="中奖用户"
              validation="required"
              placeholder="请选择用户"
              help="同一个用户不能在多个奖品里重复指定，人数不能超过当前奖品数量。"
            />
          </FormKit>
        </div>
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
