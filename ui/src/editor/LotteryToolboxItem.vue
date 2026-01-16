<script lang="ts" setup>
import type { Editor } from "@halo-dev/richtext-editor";
import { ToolboxItem } from "@halo-dev/richtext-editor";
import { markRaw } from "vue";
import RiGiftLine from "~icons/ri/gift-line";

const props = defineProps<{
  editor: Editor;
}>();

const handleClick = () => {
  props.editor
    .chain()
    .focus()
    .setLotteryCard({ name: "" })
    .run();
};
</script>

<template>
  <ToolboxItem
    :editor="editor"
    :icon="markRaw(RiGiftLine)"
    title="抽奖卡片"
    @click="handleClick"
  />
</template>
