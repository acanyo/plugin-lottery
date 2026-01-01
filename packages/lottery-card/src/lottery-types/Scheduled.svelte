<script lang="ts">
  import type { LotteryData, StatusResult, ParticipateResult, Prize, Winner } from "../types";
  import giftClosedSvg from "../assets/lihe-guanbi.svg";
  import giftOpenSvg from "../assets/lehe-dakai.svg";
  import starsSvg from "../assets/xingxing.svg";
  import jinbiSvg from "../assets/jinbi.svg";
  import hongbaoSvg from "../assets/hongbao1.svg";

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

  const stateText: Record<string, string> = {
    PENDING: "未开始",
    RUNNING: "进行中",
    ENDED: "已结束",
    DRAWN: "已开奖",
  };

  let canParticipate = $derived(
    lotteryData?.state === "RUNNING" &&
    !statusResult?.participated &&
    (!lotteryData?.participationType || lotteryData?.participationType === "NONE")
  );

  function formatTime(time?: string) {
    if (!time) return "";
    const d = new Date(time);
    return `${d.getMonth() + 1}月${d.getDate()}日 ${d.getHours()}:${String(d.getMinutes()).padStart(2, '0')} 开奖`;
  }

  function handleShowForm() {
    showForm = true;
  }

  function handleCancel() {
    showForm = false;
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

<div class="lottery-card lottery-shine">
  <!-- 头部彩色区域 -->
  <div class="lottery-header">
    <!-- 漂浮装饰 -->
    <img src={jinbiSvg} alt="" class="lottery-decor lottery-decor--1" />
    <img src={jinbiSvg} alt="" class="lottery-decor lottery-decor--2" />
    <img src={hongbaoSvg} alt="" class="lottery-decor lottery-decor--3" />
    <img src={starsSvg} alt="" class="lottery-decor lottery-decor--4" />

    <div class="lottery-header__content">
      <div class="lottery-header__top">
        <div>
          <h3 class="lottery-title">{lotteryData?.title}</h3>
          {#if lotteryData?.description}
            <p class="lottery-desc">{lotteryData.description}</p>
          {/if}
        </div>
        <span class="lottery-tag {lotteryData?.state === 'RUNNING' ? 'lottery-tag--running' : ''}">
          {stateText[lotteryData?.state || "PENDING"]}
        </span>
      </div>

      <div class="lottery-stats">
        <span class="lottery-stats__item">
          <img src={jinbiSvg} alt="" class="lottery-stats__icon" />
          {lotteryData?.participantCount || 0} 人参与
        </span>
        {#if lotteryData?.endTime}
          <span class="lottery-stats__item">⏰ {formatTime(lotteryData.endTime)}</span>
        {/if}
      </div>
    </div>
  </div>

  <!-- 内容区 -->
  <div class="lottery-body">
    <!-- 奖品展示 - 简化版 -->
    {#if lotteryData?.prizes?.length}
      <div class="lottery-prizes-section">
        <div class="lottery-prizes-title">
          🎁 奖品
        </div>
        <div class="lottery-prizes__grid">
          {#each lotteryData.prizes as prize (prize.name)}
            {@const p = prize as Prize}
            <div class="lottery-prize">
              {#if p.imageUrl}
                <img src={p.imageUrl} alt={p.name} class="lottery-prize__img" />
              {/if}
              <div class="lottery-prize__info">
                <span class="lottery-prize__name">{p.name}</span>
                {#if (p.quantity ?? 0) > 1}
                  <span class="lottery-prize__qty">×{p.quantity}</span>
                {/if}
              </div>
            </div>
          {/each}
        </div>
      </div>
    {/if}

    <!-- 中奖名单 -->
    {#if lotteryData?.state === "DRAWN" && lotteryData?.winners?.length}
      <div class="lottery-winners">
        <div class="lottery-winners__title">🎉 中奖名单</div>
        <div class="lottery-winners__list">
          {#each lotteryData.winners as winner (winner.identifier)}
            {@const w = winner as Winner}
            <div class="lottery-winner">
              <span class="lottery-winner__name">{w.identifier}</span>
              <span class="lottery-winner__prize">{w.prizeName}</span>
            </div>
          {/each}
        </div>
      </div>
    {/if}

    <!-- 操作区 -->
    {#if statusResult?.participated}
      {#if statusResult.isWinner}
        <div class="lottery-win">
          <img src={giftOpenSvg} alt="" class="lottery-win__icon" />
          <div class="lottery-win__content">
            <div class="lottery-win__title">🎉 恭喜中奖</div>
            <div class="lottery-win__prize">{statusResult.prizeName}</div>
          </div>
          <img src={starsSvg} alt="" class="lottery-win__stars" />
        </div>
      {:else}
        <div class="lottery-participated">
          <img src={giftClosedSvg} alt="" class="lottery-participated__icon" />
          <span>已参与</span>
          {#if lotteryData?.state === "DRAWN"}
            <span class="lottery-participated__miss">· 未中奖</span>
          {/if}
        </div>
      {/if}
    {:else if canParticipate}
      {#if showForm}
        <form onsubmit={handleSubmit} class="lottery-form">
          <input type="email" bind:value={email} placeholder="邮箱" required class="lottery-input" />
          <input type="text" bind:value={displayName} placeholder="昵称" class="lottery-input" />
          <button type="submit" disabled={!email || submitting} class="lottery-btn lottery-btn--primary">
            {submitting ? "提交中..." : "参与"}
          </button>
          <button type="button" onclick={handleCancel} class="lottery-btn lottery-btn--secondary">取消</button>
        </form>
      {:else}
        <button type="button" onclick={handleShowForm} class="lottery-btn lottery-btn--primary">
          <img src={giftClosedSvg} alt="" class="lottery-btn__icon" />
          立即参与
        </button>
      {/if}
    {:else if lotteryData?.state === "PENDING"}
      <div class="lottery-notice lottery-notice--pending">⏳ 活动尚未开始</div>
    {:else if lotteryData?.state !== "RUNNING"}
      <div class="lottery-notice lottery-notice--ended">🎊 活动已结束</div>
    {/if}
  </div>
</div>
