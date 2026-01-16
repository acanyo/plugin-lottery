<script lang="ts">
  import type { LotteryData, StatusResult, ParticipateResult, Prize, Winner, ParticipationType, SendCodeResult } from "../types";
  import giftClosedSvg from "../assets/lihe-guanbi.svg";
  import giftOpenSvg from "../assets/lehe-dakai.svg";
  import starsSvg from "../assets/xingxing.svg";
  import jinbiSvg from "../assets/jinbi.svg";
  import hongbaoSvg from "../assets/hongbao1.svg";

  let {
    lotteryData,
    statusResult,
    onParticipate,
    verificationEnabled = false,
    onSendCode,
    commentEmail = "",
  }: {
    lotteryData: LotteryData;
    statusResult?: StatusResult;
    participating?: boolean;
    onParticipate?: (email: string, displayName?: string, verificationCode?: string) => Promise<ParticipateResult | undefined>;
    verificationEnabled?: boolean;
    onSendCode?: (email: string) => Promise<SendCodeResult>;
    commentEmail?: string;
  } = $props();

  let email = $state("");
  let displayName = $state("");
  let verificationCode = $state("");
  let showForm = $state(false);
  let submitting = $state(false);
  let sendingCode = $state(false);
  let codeSent = $state(false);
  let countdown = $state(0);
  let errorMsg = $state("");

  const stateText: Record<string, string> = {
    PENDING: "未开始",
    RUNNING: "进行中",
    ENDED: "已结束",
    DRAWN: "已开奖",
  };

  // 参与类型提示文案
  const participationHint: Record<ParticipationType, string> = {
    NONE: "输入邮箱参与抽奖",
    LOGIN: "🔐 需要登录后参与",
    COMMENT: "💬 需要在本文评论，刷新页面后参与",
    LOGIN_AND_COMMENT: "🔐💬 需要登录并评论后参与",
  };

  // 按钮文案
  const buttonText: Record<ParticipationType, string> = {
    NONE: "立即参与",
    LOGIN: "登录参与",
    COMMENT: "立即参与",
    LOGIN_AND_COMMENT: "登录并评论参与",
  };

  // 是否需要邮箱输入（只有 NONE 类型需要）
  let needsEmail = $derived(lotteryData?.participationType === "NONE");
  // COMMENT 类型需要验证码（登录类型不需要）
  let isCommentType = $derived(lotteryData?.participationType === "COMMENT");
  // 是否需要验证码（NONE 和 COMMENT 类型需要，登录类型不需要）
  let needsVerification = $derived(verificationEnabled && (needsEmail || isCommentType));

  let canParticipate = $derived(
    lotteryData?.state === "RUNNING" && !statusResult?.participated
  );

  function formatTime(time?: string) {
    if (!time) return "";
    const d = new Date(time);
    return `${d.getMonth() + 1}月${d.getDate()}日 ${d.getHours()}:${String(d.getMinutes()).padStart(2, '0')} 开奖`;
  }

  function handleShowForm() {
    showForm = true;
    errorMsg = "";
  }

  function handleCancel() {
    showForm = false;
    errorMsg = "";
    verificationCode = "";
    codeSent = false;
  }

  async function handleSendCode() {
    // COMMENT 类型使用评论邮箱，NONE 类型使用输入的邮箱
    const targetEmail = isCommentType ? commentEmail : email;
    if (!targetEmail || sendingCode || !onSendCode) return;
    
    sendingCode = true;
    errorMsg = "";
    const result = await onSendCode(targetEmail);
    sendingCode = false;
    
    if (result.success) {
      codeSent = true;
      countdown = 60;
      const timer = setInterval(() => {
        countdown--;
        if (countdown <= 0) {
          clearInterval(timer);
        }
      }, 1000);
    } else {
      errorMsg = result.message;
    }
  }

  async function handleSubmit(e: Event) {
    e.preventDefault();
    if (!onParticipate || submitting) return;
    
    // NONE 类型必须填写邮箱，COMMENT 类型使用评论邮箱
    const targetEmail = isCommentType ? commentEmail : email;
    if (needsEmail && !email) return;
    // 需要验证码时必须填写
    if (needsVerification && !verificationCode) {
      errorMsg = "请输入验证码";
      return;
    }
    
    submitting = true;
    errorMsg = "";
    const result = await onParticipate(targetEmail, displayName || undefined, needsVerification ? verificationCode : undefined);
    submitting = false;
    
    if (result?.success) {
      showForm = false;
      verificationCode = "";
      codeSent = false;
      countdown = 0;
    } else if (result?.message) {
      errorMsg = result.message;
    }
  }

  // 直接参与（LOGIN、COMMENT、LOGIN_AND_COMMENT 类型）
  async function handleDirectParticipate() {
    if (!onParticipate || submitting) return;
    submitting = true;
    errorMsg = "";
    const result = await onParticipate("", undefined);
    submitting = false;
    
    if (!result?.success && result?.message) {
      errorMsg = result.message;
    }
  }
</script>

<div class="lottery-card lottery-shine">
  <!-- 头部彩色区域 -->
  <div class="lottery-header">
    <!-- 漂浮装饰 -->
    <img src={jinbiSvg} alt="" class="lottery-decor lottery-decor--1" style="background: transparent" />
    <img src={jinbiSvg} alt="" class="lottery-decor lottery-decor--2" style="background: transparent" />
    <img src={hongbaoSvg} alt="" class="lottery-decor lottery-decor--3" style="background: transparent" />
    <img src={starsSvg} alt="" class="lottery-decor lottery-decor--4" style="background: transparent" />

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
          <img src={jinbiSvg} alt="" class="lottery-stats__icon" style="background: transparent" />
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
                <img src={p.imageUrl} alt={p.name} class="lottery-prize__img" style="background: transparent" />
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
          <img src={giftOpenSvg} alt="" class="lottery-win__icon" style="background: transparent" />
          <div class="lottery-win__content">
            <div class="lottery-win__title">🎉 恭喜中奖</div>
            <div class="lottery-win__prize">{statusResult.prizeName}</div>
          </div>
          <img src={starsSvg} alt="" class="lottery-win__stars" style="background: transparent" />
        </div>
      {:else}
        <div class="lottery-participated">
          <img src={giftClosedSvg} alt="" class="lottery-participated__icon" style="background: transparent" />
          <span>已参与</span>
          {#if lotteryData?.state === "DRAWN"}
            <span class="lottery-participated__miss">· 未中奖</span>
          {/if}
        </div>
      {/if}
    {:else if canParticipate}
      <!-- 错误提示 -->
      {#if errorMsg}
        <div class="lottery-error">{errorMsg}</div>
      {/if}
      
      {#if needsEmail || (isCommentType && needsVerification)}
        <!-- 需要表单：NONE 类型，或 COMMENT 类型需要验证码 -->
        {#if isCommentType && !commentEmail}
          <div class="lottery-notice lottery-notice--warning">请先在本文评论，刷新页面后参与</div>
        {:else if showForm}
          <form onsubmit={handleSubmit} class="lottery-form">
            {#if needsEmail}
              <input type="email" bind:value={email} placeholder="邮箱" required class="lottery-input" />
              <input type="text" bind:value={displayName} placeholder="昵称（可选）" class="lottery-input" />
            {/if}
            {#if needsVerification}
              <div class="lottery-code-row">
                <input type="text" bind:value={verificationCode} placeholder="验证码" required class="lottery-input lottery-input--code" maxlength="6" />
                <button type="button" onclick={handleSendCode} disabled={(needsEmail && !email) || (isCommentType && !commentEmail) || sendingCode || countdown > 0} class="lottery-btn lottery-btn--code">
                  {#if sendingCode}
                    发送中...
                  {:else if countdown > 0}
                    {countdown}s
                  {:else}
                    获取验证码
                  {/if}
                </button>
              </div>
            {/if}
            <div class="lottery-form__actions">
              <button type="submit" disabled={(needsEmail && !email) || submitting || (needsVerification && !verificationCode)} class="lottery-btn lottery-btn--primary">
                {submitting ? "提交中..." : buttonText[lotteryData?.participationType || "NONE"]}
              </button>
              <button type="button" onclick={handleCancel} class="lottery-btn lottery-btn--secondary">取消</button>
            </div>
          </form>
        {:else}
          <button type="button" onclick={handleShowForm} class="lottery-btn lottery-btn--primary">
            <img src={giftClosedSvg} alt="" class="lottery-btn__icon" style="background: transparent" />
            {buttonText[lotteryData?.participationType || "NONE"]}
          </button>
        {/if}
      {:else}
        <!-- 不需要表单：LOGIN、LOGIN_AND_COMMENT，或 COMMENT 不需要验证码 -->
        <button type="button" onclick={handleDirectParticipate} disabled={submitting} class="lottery-btn lottery-btn--primary">
          <img src={giftClosedSvg} alt="" class="lottery-btn__icon" style="background: transparent" />
          {submitting ? "参与中..." : buttonText[lotteryData?.participationType || "LOGIN"]}
        </button>
      {/if}
    {:else if lotteryData?.state === "PENDING"}
      <div class="lottery-notice lottery-notice--pending">⏳ 活动尚未开始</div>
    {:else if lotteryData?.state !== "RUNNING"}
      <div class="lottery-notice lottery-notice--ended">🎊 活动已结束</div>
    {/if}
  </div>
</div>
