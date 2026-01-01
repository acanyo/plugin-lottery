<script lang="ts">
  import type { LotteryData, StatusResult } from "../types";

  let {
    lotteryData,
    statusResult,
  }: {
    lotteryData?: LotteryData;
    statusResult?: StatusResult;
    participating?: boolean;
    onParticipate?: (email: string, displayName?: string) => void;
  } = $props();

  const stateMap: Record<string, { text: string; class: string }> = {
    PENDING: { text: "待开始", class: "bg-state-pending" },
    RUNNING: { text: "进行中", class: "bg-state-running" },
    ENDED: { text: "已结束", class: "bg-state-ended" },
    DRAWN: { text: "已开奖", class: "bg-state-drawn" },
  };

  let stateInfo = $derived(stateMap[lotteryData?.state || "PENDING"]);
  let firstPrize = $derived(lotteryData?.prizes?.[0]);
</script>

<div class="w-full p-3 flex items-center gap-3">
  <!-- 奖品图片 -->
  {#if firstPrize?.imageUrl}
    <div class="w-12 h-12 flex-shrink-0 overflow-hidden rounded-lg">
      <img
        src={firstPrize.imageUrl}
        alt={firstPrize.name}
        class="w-full h-full object-cover"
        referrerpolicy="no-referrer"
      />
    </div>
  {:else}
    <div class="w-12 h-12 flex-shrink-0 bg-prize rounded-lg flex items-center justify-center">
      <span class="text-xl">🎁</span>
    </div>
  {/if}

  <!-- 信息 -->
  <div class="flex-1 min-w-0 space-y-0.5">
    <h2 class="font-medium text-sm text-title line-clamp-1">{lotteryData?.title}</h2>
    <div class="flex items-center gap-2 text-xs text-description">
      <span>{lotteryData?.participantCount || 0} 人参与</span>
      {#if firstPrize}
        <span>·</span>
        <span class="text-link">{firstPrize.name}</span>
      {/if}
    </div>
  </div>

  <!-- 状态 -->
  <div class="flex-shrink-0 flex flex-col items-end gap-1">
    <span class="{stateInfo.class} text-white text-xs px-2 py-0.5 rounded-full">
      {stateInfo.text}
    </span>
    {#if statusResult?.participated}
      {#if statusResult.isWinner}
        <span class="text-link text-xs">🎊 中奖</span>
      {:else if lotteryData?.state === "DRAWN"}
        <span class="text-description text-xs">未中奖</span>
      {:else}
        <span class="text-success text-xs">✓ 已参与</span>
      {/if}
    {/if}
  </div>
</div>

<style>
  :host {
    display: inline-block;
    width: 100%;
  }
</style>
