<script lang="ts">
  import type { LotteryData, StatusResult, ParticipateResult, Prize, Winner } from "../types";
  import giftClosedSvg from "../assets/lihe-guanbi.svg";
  import giftOpenSvg from "../assets/lehe-dakai.svg";
  import hongbaoSvg from "../assets/hongbao1.svg";
  import jinbiSvg from "../assets/jinbi.svg";
  import starsSvg from "../assets/xingxing.svg";

  let {
    lotteryData,
    statusResult,
    onParticipate,
  }: {
    lotteryData: LotteryData;
    statusResult?: StatusResult;
    participating?: boolean;
    onParticipate?: (email: string, displayName?: string) => Promise<ParticipateResult | undefined>;
  } = $props();

  let email = $state("");
  let displayName = $state("");
  let showForm = $state(false);
  let submitting = $state(false);
  let showWinners = $state(false);

  let canParticipate = $derived(
    lotteryData?.state === "RUNNING" &&
    !statusResult?.participated &&
    (!lotteryData?.participationType || lotteryData?.participationType === "NONE")
  );

  function formatTime(time?: string) {
    if (!time) return "";
    const d = new Date(time);
    return `${d.getMonth() + 1}月${d.getDate()}日 ${d.getHours()}:${String(d.getMinutes()).padStart(2, '0')}`;
  }

  async function handleSubmit(e: Event) {
    e.preventDefault();
    if (!email || !onParticipate || submitting) return;
    submitting = true;
    await onParticipate(email, displayName || undefined);
    submitting = false;
    showForm = false;
  }
</script>

<div class="lottery-s2 lottery-shine">
  <!-- 装饰元素 -->
  <img src={hongbaoSvg} alt="" class="lottery-s2__decor lottery-s2__decor--1" />
  <img src={jinbiSvg} alt="" class="lottery-s2__decor lottery-s2__decor--2" />
  <img src={starsSvg} alt="" class="lottery-s2__decor lottery-s2__decor--3" />

  <!-- 标题 -->
  <div class="lottery-s2__header">
    <h3 class="lottery-s2__title">{lotteryData?.title || "幸运抽奖"}</h3>
    {#if lotteryData?.description}
      <p class="lottery-s2__desc">{lotteryData.description}</p>
    {/if}
  </div>

  <!-- 信息 -->
  <div class="lottery-s2__meta">
    <span>{lotteryData?.participantCount || 0} 人参与</span>
    {#if lotteryData?.endTime}
      <span>{formatTime(lotteryData.endTime)} 截止</span>
    {/if}
  </div>

  <!-- 奖品展示 -->
  {#if lotteryData?.prizes?.length}
    <div class="lottery-s2__prizes">
      {#each lotteryData.prizes as prize (prize.name)}
        {@const p = prize as Prize}
        <div class="lottery-s2__prize">
          {#if p.imageUrl}
            <img src={p.imageUrl} alt={p.name} class="lottery-s2__prize-img" />
          {:else}
            <span class="lottery-s2__prize-emoji">🎁</span>
          {/if}
          <span class="lottery-s2__prize-name">{p.name}</span>
        </div>
      {/each}
    </div>
  {/if}

  <!-- 操作区 -->
  <div class="lottery-s2__action">
    {#if statusResult?.participated}
      {#if statusResult.isWinner}
        <div class="lottery-s2__win">
          <span class="lottery-s2__win-emoji">🎉</span>
          <div>
            <div class="lottery-s2__win-label">恭喜中奖</div>
            <div class="lottery-s2__win-prize">{statusResult.prizeName}</div>
          </div>
        </div>
      {:else}
        <div class="lottery-s2__done">
          ✓ 已参与{lotteryData?.state === "DRAWN" ? " · 未中奖" : ""}
        </div>
      {/if}
    {:else if canParticipate}
      {#if showForm}
        <form onsubmit={handleSubmit} class="lottery-s2__form">
          <input type="email" bind:value={email} placeholder="邮箱" required class="lottery-s2__input" />
          <input type="text" bind:value={displayName} placeholder="昵称(选填)" class="lottery-s2__input" />
          <div class="lottery-s2__form-btns">
            <button type="button" onclick={() => showForm = false} class="lottery-s2__btn lottery-s2__btn--ghost">取消</button>
            <button type="submit" disabled={!email || submitting} class="lottery-s2__btn lottery-s2__btn--gold">
              {submitting ? "提交中..." : "确认参与"}
            </button>
          </div>
        </form>
      {:else}
        <button type="button" onclick={() => showForm = true} class="lottery-s2__btn lottery-s2__btn--gold lottery-s2__btn--big">
          🎯 立即参与
        </button>
      {/if}
    {:else if lotteryData?.state === "PENDING"}
      <div class="lottery-s2__notice">⏳ 活动尚未开始</div>
    {:else}
      <div class="lottery-s2__notice">🎊 活动已结束</div>
    {/if}
  </div>

  <!-- 中奖名单 -->
  {#if lotteryData?.state === "DRAWN" && lotteryData?.winners?.length}
    <div class="lottery-s2__winners-section">
      <button type="button" class="lottery-s2__winners-toggle" onclick={() => showWinners = !showWinners}>
        🏆 中奖名单 {showWinners ? "▲" : "▼"}
      </button>
      {#if showWinners}
        <div class="lottery-s2__winners">
          {#each lotteryData.winners as winner (winner.identifier)}
            {@const w = winner as Winner}
            <div class="lottery-s2__winner">
              <span>{w.identifier}</span>
              <span class="lottery-s2__winner-prize">{w.prizeName}</span>
            </div>
          {/each}
        </div>
      {/if}
    </div>
  {/if}
</div>
