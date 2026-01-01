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
  let displayName = $state("");
  let showForm = $state(false);

  const stateMap: Record<string, { text: string; class: string }> = {
    PENDING: { text: "待开始", class: "bg-state-pending" },
    RUNNING: { text: "进行中", class: "bg-state-running" },
    ENDED: { text: "已结束", class: "bg-state-ended" },
    DRAWN: { text: "已开奖", class: "bg-state-drawn" },
  };

  const participationTypeMap: Record<string, string> = {
    NONE: "无条件参与",
    LOGIN: "需登录参与",
    COMMENT: "需评论参与",
    LOGIN_AND_COMMENT: "需登录并评论",
  };

  let stateInfo = $derived(stateMap[lotteryData?.state || "PENDING"]);
  let canParticipate = $derived(
    lotteryData?.state === "RUNNING" &&
    !statusResult?.participated &&
    lotteryData?.participationType === "NONE"
  );

  function formatTime(time?: string) {
    if (!time) return "未设置";
    return new Date(time).toLocaleString("zh-CN");
  }

  function handleSubmit(e: Event) {
    e.preventDefault();
    if (email && onParticipate) {
      onParticipate(email, displayName || undefined);
    }
  }
</script>

<div class="w-full p-4 space-y-4">
  <!-- 头部：标题和状态 -->
  <div class="flex items-start justify-between gap-3">
    <div class="flex-1 min-w-0">
      <h2 class="font-bold text-lg text-title line-clamp-2">{lotteryData?.title}</h2>
      {#if lotteryData?.description}
        <p class="text-sm text-description mt-1 line-clamp-2">{lotteryData.description}</p>
      {/if}
    </div>
    <span class="{stateInfo.class} text-white text-xs px-2 py-1 rounded-full whitespace-nowrap">
      {stateInfo.text}
    </span>
  </div>

  <!-- 奖品展示 -->
  {#if lotteryData?.prizes && lotteryData.prizes.length > 0}
    <div class="space-y-2">
      <h3 class="text-sm font-medium text-title">奖品</h3>
      <div class="flex flex-wrap gap-2">
        {#each lotteryData.prizes as prize}
          <div class="flex items-center gap-2 bg-prize border border-prize rounded-lg px-3 py-2">
            {#if prize.imageUrl}
              <img
                src={prize.imageUrl}
                alt={prize.name}
                class="w-8 h-8 rounded object-cover"
                referrerpolicy="no-referrer"
              />
            {/if}
            <div class="text-sm">
              <span class="text-title font-medium">{prize.name}</span>
              {#if prize.quantity && prize.quantity > 1}
                <span class="text-description"> x{prize.quantity}</span>
              {/if}
            </div>
          </div>
        {/each}
      </div>
    </div>
  {/if}

  <!-- 活动信息 -->
  <div class="grid grid-cols-2 gap-2 text-sm">
    <div>
      <span class="text-description">参与条件：</span>
      <span class="text-title">{participationTypeMap[lotteryData?.participationType || "NONE"]}</span>
    </div>
    <div>
      <span class="text-description">参与人数：</span>
      <span class="text-title">{lotteryData?.participantCount || 0}</span>
      {#if lotteryData?.maxParticipants}
        <span class="text-description">/{lotteryData.maxParticipants}</span>
      {/if}
    </div>
    {#if lotteryData?.startTime}
      <div>
        <span class="text-description">开始时间：</span>
        <span class="text-title text-xs">{formatTime(lotteryData.startTime)}</span>
      </div>
    {/if}
    {#if lotteryData?.endTime}
      <div>
        <span class="text-description">结束时间：</span>
        <span class="text-title text-xs">{formatTime(lotteryData.endTime)}</span>
      </div>
    {/if}
  </div>

  <!-- 中奖者展示 -->
  {#if lotteryData?.state === "DRAWN" && lotteryData?.winners && lotteryData.winners.length > 0}
    <div class="space-y-2">
      <h3 class="text-sm font-medium text-title">🎉 中奖名单</h3>
      <div class="space-y-1">
        {#each lotteryData.winners as winner}
          <div class="flex items-center justify-between text-sm bg-prize/50 rounded px-3 py-2">
            <span class="text-title">{winner.identifier}</span>
            <span class="text-link font-medium">{winner.prizeName}</span>
          </div>
        {/each}
      </div>
    </div>
  {/if}

  <!-- 参与状态/操作 -->
  <div class="pt-2 border-t border-card">
    {#if statusResult?.participated}
      <div class="text-center space-y-2">
        <p class="text-success text-sm">✓ 您已参与此活动</p>
        {#if statusResult.isWinner}
          <p class="text-link font-bold">🎊 恭喜中奖：{statusResult.prizeName}</p>
        {:else if lotteryData?.state === "DRAWN"}
          <p class="text-description text-sm">很遗憾，未能中奖</p>
        {/if}
      </div>
    {:else if canParticipate}
      {#if showForm}
        <form onsubmit={handleSubmit} class="space-y-3">
          <div>
            <input
              type="email"
              bind:value={email}
              placeholder="请输入邮箱 *"
              required
              class="w-full px-3 py-2 border border-card rounded-lg text-sm focus:outline-none focus:border-link"
            />
          </div>
          <div>
            <input
              type="text"
              bind:value={displayName}
              placeholder="昵称（可选）"
              class="w-full px-3 py-2 border border-card rounded-lg text-sm focus:outline-none focus:border-link"
            />
          </div>
          <div class="flex gap-2">
            <button
              type="submit"
              disabled={participating || !email}
              class="flex-1 bg-btn bg-btn-hover text-btn py-2 px-4 rounded-lg text-sm font-medium transition-colors disabled:bg-btn-disabled disabled:cursor-not-allowed"
            >
              {participating ? "提交中..." : "确认参与"}
            </button>
            <button
              type="button"
              onclick={() => (showForm = false)}
              class="px-4 py-2 border border-card rounded-lg text-sm text-description hover:bg-card"
            >
              取消
            </button>
          </div>
        </form>
      {:else}
        <button
          onclick={() => (showForm = true)}
          class="w-full bg-btn bg-btn-hover text-btn py-2 px-4 rounded-lg text-sm font-medium transition-colors"
        >
          立即参与
        </button>
      {/if}
    {:else if lotteryData?.state === "PENDING"}
      <p class="text-center text-description text-sm">活动尚未开始</p>
    {:else if lotteryData?.state === "ENDED" || lotteryData?.state === "DRAWN"}
      <p class="text-center text-description text-sm">活动已结束</p>
    {:else if lotteryData?.participationType !== "NONE"}
      <p class="text-center text-description text-sm">
        {participationTypeMap[lotteryData?.participationType || "NONE"]}
      </p>
    {/if}
  </div>
</div>

<style>
  :host {
    display: inline-block;
    width: 100%;
  }
</style>
