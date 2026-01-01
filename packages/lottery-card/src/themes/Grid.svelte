<script lang="ts">
  import type { LotteryData, StatusResult } from "../types";

  let {
    lotteryData,
    statusResult,
    participating,
    onParticipate,
  }: {
    lotteryData?: LotteryData;
    statusResult?: StatusResult;
    participating?: boolean;
    onParticipate?: (email: string, displayName?: string) => void;
  } = $props();

  let email = $state("");
  let showForm = $state(false);

  const stateMap: Record<string, { text: string; class: string }> = {
    PENDING: { text: "待开始", class: "bg-state-pending" },
    RUNNING: { text: "进行中", class: "bg-state-running" },
    ENDED: { text: "已结束", class: "bg-state-ended" },
    DRAWN: { text: "已开奖", class: "bg-state-drawn" },
  };

  let stateInfo = $derived(stateMap[lotteryData?.state || "PENDING"]);
  let canParticipate = $derived(
    lotteryData?.state === "RUNNING" &&
    !statusResult?.participated &&
    lotteryData?.participationType === "NONE"
  );
  let firstPrize = $derived(lotteryData?.prizes?.[0]);

  function handleSubmit(e: Event) {
    e.preventDefault();
    if (email && onParticipate) {
      onParticipate(email);
    }
  }
</script>

<div class="w-full p-3 space-y-3">
  <!-- 奖品图片 -->
  {#if firstPrize?.imageUrl}
    <div class="aspect-video w-full overflow-hidden rounded-lg">
      <img
        src={firstPrize.imageUrl}
        alt={firstPrize.name}
        class="w-full h-full object-cover"
        referrerpolicy="no-referrer"
      />
    </div>
  {/if}

  <!-- 标题和状态 -->
  <div class="space-y-1">
    <div class="flex items-center justify-between gap-2">
      <h2 class="font-bold text-base text-title line-clamp-1 flex-1">{lotteryData?.title}</h2>
      <span class="{stateInfo.class} text-white text-xs px-2 py-0.5 rounded-full">
        {stateInfo.text}
      </span>
    </div>
    {#if lotteryData?.description}
      <p class="text-xs text-description line-clamp-2">{lotteryData.description}</p>
    {/if}
  </div>

  <!-- 简要信息 -->
  <div class="flex items-center justify-between text-xs text-description">
    <span>{lotteryData?.participantCount || 0} 人参与</span>
    {#if firstPrize}
      <span class="text-link">{firstPrize.name}</span>
    {/if}
  </div>

  <!-- 参与状态/操作 -->
  {#if statusResult?.participated}
    <div class="text-center py-1">
      {#if statusResult.isWinner}
        <span class="text-link text-sm font-bold">🎊 中奖：{statusResult.prizeName}</span>
      {:else if lotteryData?.state === "DRAWN"}
        <span class="text-description text-xs">未中奖</span>
      {:else}
        <span class="text-success text-xs">✓ 已参与</span>
      {/if}
    </div>
  {:else if canParticipate}
    {#if showForm}
      <form onsubmit={handleSubmit} class="space-y-2">
        <input
          type="email"
          bind:value={email}
          placeholder="输入邮箱参与"
          required
          class="w-full px-2 py-1.5 border border-card rounded text-xs focus:outline-none focus:border-link"
        />
        <div class="flex gap-2">
          <button
            type="submit"
            disabled={participating || !email}
            class="flex-1 bg-btn bg-btn-hover text-btn py-1.5 rounded text-xs font-medium disabled:bg-btn-disabled"
          >
            {participating ? "..." : "确认"}
          </button>
          <button
            type="button"
            onclick={() => (showForm = false)}
            class="px-3 py-1.5 border border-card rounded text-xs text-description"
          >
            取消
          </button>
        </div>
      </form>
    {:else}
      <button
        onclick={() => (showForm = true)}
        class="w-full bg-btn bg-btn-hover text-btn py-1.5 rounded text-xs font-medium transition-colors"
      >
        立即参与
      </button>
    {/if}
  {:else}
    <div class="text-center py-1">
      <span class="text-description text-xs">
        {#if lotteryData?.state === "PENDING"}
          活动未开始
        {:else}
          活动已结束
        {/if}
      </span>
    </div>
  {/if}
</div>

<style>
  :host {
    display: inline-block;
    width: 100%;
  }
</style>
