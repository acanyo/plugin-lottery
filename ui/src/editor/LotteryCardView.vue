<script lang="ts" setup>
import type { NodeViewProps } from "@halo-dev/richtext-editor";
import { NodeViewWrapper } from "@halo-dev/richtext-editor";
import { computed } from "vue";
import { VEmpty } from "@halo-dev/components";
import RiGiftLine from "~icons/ri/gift-line";
import "@xhhao-com/lottery-card";
import "@xhhao-com/lottery-card/style.css";

const props = defineProps<NodeViewProps>();

const lotteryName = computed({
  get: () => {
    return (props.node?.attrs.name as string) || "";
  },
  set: (value: string) => {
    props.updateAttributes({ name: value });
  },
});
</script>

<template>
  <node-view-wrapper
    as="div"
    :class="[
      'lottery-card-editor-wrapper',
      { 'lottery-card-editor-wrapper--selected': selected },
    ]"
  >
    <div class="lottery-card-nav">
      <div class="lottery-card-nav-start">
        <RiGiftLine class="lottery-card-nav-icon" />
        <span>抽奖卡片</span>
      </div>
      <div class="lottery-card-nav-end">
        <FormKit
          v-model="lotteryName"
          type="select"
          name="lotteryName"
          :multiple="false"
          clearable
          searchable
          placeholder="请选择抽奖活动"
          action="/apis/console.api.lottery.xhhao.com/v1alpha1/lotteries?state=RUNNING&state=PENDING"
          :request-option="{
            method: 'GET',
            pageField: 'page',
            sizeField: 'size',
            totalField: 'total',
            itemsField: 'items',
            labelField: 'spec.title',
            valueField: 'metadata.name',
          }"
          :classes="{
            wrapper: 'lottery-select-wrapper',
            input: 'lottery-select-input',
          }"
        />
      </div>
    </div>
    <div class="lottery-card-preview">
      <div v-if="lotteryName" class="lottery-card-preview-content">
        <lottery-card :name="lotteryName"></lottery-card>
      </div>
      <VEmpty v-else message="请在上方选择抽奖活动" title="未选择活动" />
    </div>
  </node-view-wrapper>
</template>

<style>
.lottery-card-editor-wrapper {
  --tw-ring-offset-shadow: var(--tw-ring-inset) 0 0 0
    var(--tw-ring-offset-width) var(--tw-ring-offset-color);
  --tw-ring-shadow: var(--tw-ring-inset) 0 0 0
    calc(1px + var(--tw-ring-offset-width)) var(--tw-ring-color);
  box-shadow: var(--tw-ring-offset-shadow), var(--tw-ring-shadow),
    var(--tw-shadow, 0 0 #0000);
  --tw-ring-opacity: 1;
  --tw-ring-color: rgb(229 231 235 / var(--tw-ring-opacity));
  border-radius: 8px;
  overflow: hidden;
  margin: 0.75em 0;
}

.lottery-card-editor-wrapper--selected {
  --tw-ring-offset-shadow: var(--tw-ring-inset) 0 0 0
    var(--tw-ring-offset-width) var(--tw-ring-offset-color);
  --tw-ring-shadow: var(--tw-ring-inset) 0 0 0
    calc(2px + var(--tw-ring-offset-width)) var(--tw-ring-color);
  box-shadow: var(--tw-ring-offset-shadow), var(--tw-ring-shadow),
    var(--tw-shadow, 0 0 #0000);
  --tw-ring-color: inherit;
}

.lottery-card-nav {
  border-bottom: 1px #e7e7e7 solid;
  display: flex;
  padding: 8px 12px;
  align-items: center;
  background: #fafafa;
}

.lottery-card-nav-start {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #374151;
}

.lottery-card-nav-icon {
  width: 18px;
  height: 18px;
  color: #f43f5e;
}

.lottery-card-nav-end {
  justify-content: flex-end;
}

.lottery-card-preview {
  padding: 12px;
  background: transparent;
  min-height: 100px;
}

/* 编辑器中覆盖 lottery-card 的白色背景 */
.lottery-card-preview .lottery-body,
.lottery-card-preview-content .lottery-body {
  background: transparent !important;
}

.lottery-card-preview .lottery-card,
.lottery-card-preview-content .lottery-card {
  background: transparent !important;
  box-shadow: none !important;
}

/* 覆盖奖品区域背景 */
.lottery-card-preview .lottery-prize,
.lottery-card-preview-content .lottery-prize {
  background: rgba(249, 250, 251, 0.5) !important;
}

/* 覆盖结果框背景 */
.lottery-card-preview .draw__result-box,
.lottery-card-preview-content .draw__result-box {
  background: rgba(255, 255, 255, 0.8) !important;
}

/* 覆盖 markdown-body 对 img 的白色背景 */
.lottery-card-preview img,
.lottery-card-preview-content img,
.lottery-card-editor-wrapper img {
  background-color: transparent !important;
  background: transparent !important;
}

.lottery-card-preview-content {
  display: block;
}

.lottery-card-preview-content lottery-card {
  display: block;
}

.lottery-select-wrapper {
  min-width: 200px;
}

.lottery-select-input {
  font-size: 14px;
}
</style>
