<script lang="ts" setup>
import { computed, inject, ref, watch, type Ref } from "vue";
import { consoleApiClient, type Plugin } from "@halo-dev/api-client";
import type { RawAxiosRequestConfig } from "axios";
import {
  Dialog,
  Toast,
  VAlert,
  VButton,
  VCard,
  VEmpty,
  VLoading,
  VSpace,
  VStatusDot,
} from "@halo-dev/components";
import { submitForm } from "@formkit/core";
import type {
  PluginRedisConfig,
  RedisConfigStatus,
  RedisConnectionTestResult,
} from "@/api/generated";
import { lotteryConsoleApi } from "@/api";

const loading = ref(true);
const saving = ref(false);
const testing = ref(false);
const status = ref<RedisConfigStatus | null>(null);
const lastTestResult = ref<RedisConnectionTestResult | null>(null);
const plugin = inject<Ref<Plugin | undefined>>("plugin");
const REDIS_GROUP = "redis";

const formState = ref<PluginRedisConfig>({
  host: "",
  port: 6379,
  database: 0,
  password: "",
});

const syncFormState = (value?: PluginRedisConfig) => {
  formState.value = {
    host: value?.host || "",
    port: value?.port ?? 6379,
    database: value?.database ?? 0,
    password: value?.password || "",
  };
};

const getPersistedFormState = (): PluginRedisConfig => {
  return {
    host: formState.value.host?.trim() || "",
    port: formState.value.port ?? 6379,
    database: formState.value.database ?? 0,
    password: formState.value.password || "",
  };
};

const buildPersistedConfig = (overrides?: Partial<PluginRedisConfig>): PluginRedisConfig => {
  return {
    ...getPersistedFormState(),
    connectionVerified: false,
    verifiedSource: undefined,
    verifiedSignature: undefined,
    verifiedAt: undefined,
    ...overrides,
  };
};

const updateRedisGroup = async (payload: PluginRedisConfig) => {
  const pluginName = plugin?.value?.metadata.name;
  if (!pluginName) {
    throw new Error("Plugin name is missing");
  }

  const { data: currentConfig } = await consoleApiClient.plugin.plugin.fetchPluginJsonConfig({
    name: pluginName,
  });
  const currentConfigMap = (currentConfig || {}) as Record<string, PluginRedisConfig>;

  await consoleApiClient.plugin.plugin.updatePluginJsonConfig({
    name: pluginName,
    body: {
      ...currentConfigMap,
      [REDIS_GROUP]: payload,
    },
  });
};

const fetchData = async () => {
  const pluginName = plugin?.value?.metadata.name;
  if (!pluginName) {
    return;
  }

  try {
    loading.value = true;
    const [statusResponse, pluginConfigResponse] = await Promise.all([
      lotteryConsoleApi.getLotteryRedisConfig(),
      consoleApiClient.plugin.plugin.fetchPluginJsonConfig({
        name: pluginName,
      }),
    ]);

    status.value = statusResponse.data;
    const pluginConfigData = pluginConfigResponse.data as
      | Record<string, PluginRedisConfig>
      | undefined;
    syncFormState(pluginConfigData?.[REDIS_GROUP]);
  } catch (error) {
    console.error("Failed to load redis config status", error);
    Toast.error("加载 Redis 配置失败");
  } finally {
    loading.value = false;
  }
};

const handleSave = async () => {
  const pluginName = plugin?.value?.metadata.name;
  if (!pluginName) {
    Toast.error("无法识别当前插件");
    return;
  }

  try {
    saving.value = true;
    await updateRedisGroup(buildPersistedConfig());

    await fetchData();
    Toast.success("Redis 配置已保存，请测试连接后再启用即时开奖");
  } catch (error) {
    console.error("Failed to save redis config", error);
    Toast.error("保存 Redis 配置失败");
  } finally {
    saving.value = false;
  }
};

const handleSubmit = () => {
  submitForm("lottery-redis-config-form");
};

const handleClear = () => {
  Dialog.warning({
    title: "确认清空插件 Redis 配置？",
    description: "清空后将回退使用 Halo 全局 Redis 配置，未保存前不会写入服务器。",
    confirmText: "确认清空",
    cancelText: "取消",
    onConfirm: async () => {
      syncFormState();
      lastTestResult.value = null;
      Toast.success("已清空表单内容");
    },
  });
};

const handleTest = async () => {
  try {
    testing.value = true;
    const requestOptions = {
      data: {
        host: formState.value.host,
        port: formState.value.port,
        database: formState.value.database,
        password: formState.value.password,
      },
    } as RawAxiosRequestConfig;
    const { data } = await lotteryConsoleApi.testLotteryRedisConfig(requestOptions);
    lastTestResult.value = data;
    if (lastTestResult.value.success) {
      await updateRedisGroup(
        buildPersistedConfig({
          connectionVerified: true,
          verifiedSource: lastTestResult.value.source,
          verifiedSignature: lastTestResult.value.verificationSignature,
          verifiedAt: Date.now(),
        })
      );
      await fetchData();
      Toast.success("Redis 连接测试成功");
    } else {
      await fetchData();
      Toast.warning(lastTestResult.value.message || "Redis 连接测试失败");
    }
  } catch (error) {
    console.error("Failed to test redis config", error);
    Toast.error("Redis 连接测试失败");
  } finally {
    testing.value = false;
  }
};

const effectiveSourceText = computed(() => {
  switch (status.value?.effectiveSource) {
    case "PLUGIN":
      return "插件配置";
    case "HALO":
      return "Halo 全局配置";
    default:
      return "未启用";
  }
});

const effectiveSourceState = computed(() => {
  switch (status.value?.effectiveSource) {
    case "PLUGIN":
      return "success";
    case "HALO":
      return "default";
    default:
      return "default";
  }
});

const effectiveEndpoint = computed(() => {
  const config = status.value?.effectiveConfig;
  if (!config?.host) {
    return "未检测到可用连接";
  }
  return `${config.host}:${config.port}`;
});

const haloEndpoint = computed(() => {
  const config = status.value?.haloConfig;
  if (!config?.configured || !config.host) {
    return "未配置";
  }
  return `${config.host}:${config.port}`;
});

watch(
  () => plugin?.value?.metadata.name,
  (pluginName) => {
    if (pluginName) {
      fetchData();
    }
  },
  { immediate: true }
);
</script>

<template>
  <VLoading v-if="loading" />
  <VEmpty
    v-else-if="!status"
    title="无法读取 Redis 配置"
    message="请刷新页面后重试"
  />
  <div v-else class="w-full max-w-3xl space-y-3">
    <VCard :body-class="['!p-0']">
      <div class="space-y-4 p-5">
        <VAlert
          :type="status.instantLotteryAvailable ? 'success' : 'warning'"
          title="提示"
          :description="status.message"
          :closable="false"
        />

        <div class="space-y-1 rounded-md border border-gray-100 bg-gray-50 px-4 py-3">
          <div class="flex items-start gap-3 py-2 text-sm">
            <span class="w-24 shrink-0 text-gray-600">当前生效来源</span>
            <VStatusDot :state="effectiveSourceState" :text="effectiveSourceText" />
          </div>
          <div class="flex items-start gap-3 py-2 text-sm">
            <span class="w-24 shrink-0 text-gray-600">当前连接</span>
            <span class="font-medium text-gray-900">{{ effectiveEndpoint }}</span>
          </div>
          <div class="flex items-start gap-3 py-2 text-sm">
            <span class="w-24 shrink-0 text-gray-600">即时开奖</span>
            <VStatusDot
              :state="status.instantLotteryAvailable ? 'success' : 'warning'"
              :text="status.instantLotteryAvailable ? '可启用' : '建议禁用'"
            />
          </div>
        </div>

        <div
          v-if="lastTestResult"
          class="rounded-md border border-gray-100 bg-gray-50 px-4 py-3 text-sm"
        >
          <div class="font-medium text-gray-900">
            {{ lastTestResult.success ? "最近一次测试成功" : "最近一次测试失败" }}
          </div>
          <div class="mt-1 text-gray-600">{{ lastTestResult.message }}</div>
          <div v-if="lastTestResult.latencyMs" class="mt-1 text-xs text-gray-500">
            耗时 {{ lastTestResult.latencyMs }} ms
          </div>
        </div>
      </div>
    </VCard>

    <VCard :body-class="['!p-0']">
      <div class="border-b border-gray-100 px-5 py-4">
        <div class="flex items-start justify-between gap-4">
          <div>
            <div class="text-base font-medium text-gray-900">插件 Redis 配置</div>
            <div class="mt-1 text-sm text-gray-500">
              填写后优先使用插件自己的 Redis；留空则自动回退到 Halo 全局配置。
            </div>
          </div>
          <VSpace class="shrink-0 redis-actions">
            <VButton type="danger" @click="handleClear">
              清空
            </VButton>
            <VButton :loading="testing" @click="handleTest">测试连接</VButton>
            <VButton type="primary" :loading="saving" @click="handleSubmit">保存</VButton>
          </VSpace>
        </div>
      </div>

      <FormKit
        id="lottery-redis-config-form"
        type="form"
        :actions="false"
        @submit="handleSave"
      >
        <div class="space-y-5 px-5 py-5">
          <FormKit
            v-model="formState.host"
            type="text"
            name="host"
            label="Redis 主机"
            placeholder="如 127.0.0.1 / redis.internal"
          />

          <FormKit
            v-model="formState.port"
            type="number"
            name="port"
            label="端口"
            min="1"
            max="65535"
          />

          <FormKit
            v-model="formState.database"
            type="number"
            name="database"
            label="数据库"
            min="0"
          />

          <FormKit
            v-model="formState.password"
            type="password"
            name="password"
            label="密码"
            placeholder="可留空"
          />
        </div>
      </FormKit>
    </VCard>

    <VCard :body-class="['!p-0']">
      <div class="border-b border-gray-100 px-5 py-4">
        <div class="text-base font-medium text-gray-900">Halo 全局 Redis 配置</div>
        <div class="mt-1 text-sm text-gray-500">
          当插件配置留空时，将自动尝试复用以下配置。
        </div>
      </div>

      <div class="divide-y divide-gray-100 px-5">
        <div class="flex items-center justify-between py-4 text-sm">
          <span class="text-gray-600">连接地址</span>
          <span class="font-medium text-gray-900">{{ haloEndpoint }}</span>
        </div>
        <div class="flex items-center justify-between py-4 text-sm">
          <span class="text-gray-600">数据库</span>
          <span class="font-medium text-gray-900">{{ status.haloConfig?.database ?? 0 }}</span>
        </div>
        <div class="flex items-center justify-between py-4 text-sm">
          <span class="text-gray-600">halo.redis.enabled</span>
          <VStatusDot
            :state="status.haloConfig?.enabled ? 'success' : 'default'"
            :text="status.haloConfig?.enabled ? '已启用' : '未启用'"
          />
        </div>
        <div class="flex items-center justify-between py-4 text-sm">
          <span class="text-gray-600">session store</span>
          <span class="font-medium text-gray-900">
            {{ status.haloConfig?.sessionStoreType || "in-memory" }}
          </span>
        </div>
        <div class="flex items-center justify-between py-4 text-sm">
          <span class="text-gray-600">密码状态</span>
          <span class="font-medium text-gray-900">
            {{ status.haloConfig?.passwordConfigured ? "已配置" : "未配置" }}
          </span>
        </div>
      </div>
    </VCard>
  </div>
</template>

<style scoped>
.redis-actions :deep(button) {
  min-width: 112px;
}
</style>
