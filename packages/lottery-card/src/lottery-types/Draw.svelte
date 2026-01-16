<script lang="ts">
  import type { LotteryData, StatusResult, ParticipateResult, ParticipationType, SendCodeResult } from "../types";
  import confetti from "canvas-confetti";
  import qiantongSvg from "../assets/qiantong.svg";

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
  let verificationCode = $state("");
  let showEmailInput = $state(false);
  let drawing = $state(false);
  let sendingCode = $state(false);
  let countdown = $state(0);
  let result = $state<ParticipateResult | undefined>();
  let showResult = $state(false);
  let errorMsg = $state("");

  // 参与类型提示文案
  const participationHint: Record<ParticipationType, string> = {
    NONE: "输入邮箱参与抽签",
    LOGIN: "🔐 需要登录后参与",
    COMMENT: "💬 需要在本文评论，刷新页面后参与",
    LOGIN_AND_COMMENT: "🔐💬 需要登录并评论后参与",
  };

  // 是否需要邮箱输入（只有 NONE 类型需要）
  let needsEmail = $derived(lotteryData?.participationType === "NONE");
  // COMMENT 类型
  let isCommentType = $derived(lotteryData?.participationType === "COMMENT");
  // 是否需要验证码（NONE 和 COMMENT 类型需要，登录类型不需要）
  let needsVerification = $derived(verificationEnabled && (needsEmail || isCommentType));
  // 是否需要显示输入表单
  let needsForm = $derived(needsEmail || (isCommentType && needsVerification));

  let canParticipate = $derived(
    lotteryData?.state === "RUNNING" && !statusResult?.participated
  );

  function fireConfetti() {
    const colors = ["#f43f5e", "#fbbf24", "#22c55e", "#3b82f6", "#a855f7"];
    confetti({ particleCount: 100, spread: 70, origin: { y: 0.6 }, colors });
  }

  async function handleDraw() {
    if (drawing || !onParticipate) return;
    // NONE 类型必须填写邮箱，COMMENT 类型使用评论邮箱
    const targetEmail = isCommentType ? commentEmail : email;
    if (needsEmail && !email) return;
    // 需要验证码时必须填写
    if (needsVerification && !verificationCode) {
      errorMsg = "请输入验证码";
      return;
    }
    
    drawing = true;
    result = undefined;
    showResult = false;
    errorMsg = "";

    const apiResult = await onParticipate(targetEmail, undefined, needsVerification ? verificationCode : undefined);
    result = apiResult;

    if (!apiResult?.success) {
      drawing = false;
      showResult = true;
      errorMsg = apiResult?.message || "";
      return;
    }
    // 参与成功后清除验证码和倒计时
    verificationCode = "";
    countdown = 0;

    setTimeout(() => {
      drawing = false;
      showResult = true;
      if (apiResult?.isWinner) fireConfetti();
    }, 1500);
  }

  async function handleSendCode() {
    const targetEmail = isCommentType ? commentEmail : email;
    if (!targetEmail || sendingCode || !onSendCode) return;
    
    sendingCode = true;
    errorMsg = "";
    const codeResult = await onSendCode(targetEmail);
    sendingCode = false;
    
    if (codeResult.success) {
      countdown = 60;
      const timer = setInterval(() => {
        countdown--;
        if (countdown <= 0) {
          clearInterval(timer);
        }
      }, 1000);
    } else {
      errorMsg = codeResult.message;
    }
  }

  function handleEmailSubmit(e: Event) {
    e.preventDefault();
    // NONE 类型需要邮箱，COMMENT 类型需要验证码
    if (needsEmail && !email) {
      errorMsg = "请输入邮箱";
      return;
    }
    if (needsVerification && !verificationCode) {
      errorMsg = "请输入验证码";
      return;
    }
    showEmailInput = false;
    handleDraw();
  }

  function handleClick() {
    if (canParticipate && !drawing && !showResult) {
      if (needsForm) {
        // 需要表单：NONE 类型需要邮箱，COMMENT 类型需要验证码
        if (needsEmail && email && (!needsVerification || verificationCode)) {
          handleDraw();
        } else if (isCommentType && commentEmail && (!needsVerification || verificationCode)) {
          handleDraw();
        } else {
          showEmailInput = true;
        }
      } else {
        // 不需要表单（登录类型）：直接抽签
        handleDraw();
      }
    }
  }
</script>

<div class="draw lottery-shine">
  <div class="draw__header">
    <h2 class="draw__title">{lotteryData?.title || "幸运抽签"}</h2>
    <p class="draw__subtitle">{lotteryData?.description || "点击签筒抽取好运"}</p>
  </div>

  <div class="draw__body">
    <div 
      class="draw__bucket" 
      class:draw__bucket--drawing={drawing}
      class:draw__bucket--disabled={!canParticipate || showResult}
      onclick={handleClick} 
      onkeydown={(e) => e.key === 'Enter' && handleClick()} 
      role="button" 
      tabindex="0"
    >
      <img src={qiantongSvg} alt="签筒" class="draw__bucket-img" style="background: transparent" />
    </div>
    
    {#if canParticipate && !drawing && !showResult && !showEmailInput}
      <p class="draw__hint">👆 点击签筒抽签</p>
    {/if}
  </div>

  {#if showEmailInput && canParticipate && needsForm}
    {#if isCommentType && !commentEmail}
      <div class="draw__login-hint draw__login-hint--warning">请先在本文评论，刷新页面后参与</div>
    {:else}
      <form onsubmit={handleEmailSubmit} class="draw__form">
        <div class="draw__form-inputs">
          {#if needsEmail}
            <input type="email" bind:value={email} placeholder="输入邮箱参与抽签" required />
          {/if}
          {#if needsVerification}
            <div class="draw__code-row">
              <input type="text" bind:value={verificationCode} placeholder="验证码" required maxlength="6" class="draw__code-input" />
              <button type="button" onclick={handleSendCode} disabled={(needsEmail && !email) || (isCommentType && !commentEmail) || sendingCode || countdown > 0} class="draw__code-btn">
                {#if sendingCode}发送中{:else if countdown > 0}{countdown}s{:else}获取验证码{/if}
              </button>
            </div>
          {/if}
          {#if errorMsg}
            <div class="draw__error">{errorMsg}</div>
          {/if}
        </div>
        <button type="submit" disabled={(needsEmail && !email) || (needsVerification && !verificationCode)}>开始抽签</button>
      </form>
    {/if}
  {:else if canParticipate && !showResult && !drawing && !needsForm}
    <!-- 参与类型提示 -->
    <div class="draw__login-hint">{participationHint[lotteryData?.participationType || "NONE"]}</div>
  {/if}

  {#if showResult && result}
    <div class="draw__result">
      {#if result.success && result.isWinner}
        <div class="draw__result-box draw__result-box--win">
          <div class="draw__qian-type">🎊 上上签</div>
          <div class="draw__prize">恭喜获得「{result.prizeName}」</div>
        </div>
      {:else if result.success}
        <div class="draw__result-box draw__result-box--lose">
          <div class="draw__qian-type">📜 下下签</div>
          <div class="draw__blessing">虽未中奖，好运常伴左右</div>
        </div>
      {:else}
        <div class="draw__result-box draw__result-box--error">{result.message}</div>
      {/if}
    </div>
  {:else if statusResult?.participated}
    <div class="draw__result">
      {#if statusResult.isWinner}
        <div class="draw__result-box draw__result-box--win">
          <div class="draw__qian-type">🎊 上上签</div>
          <div class="draw__prize">已获得「{statusResult.prizeName}」</div>
        </div>
      {:else}
        <div class="draw__result-box draw__result-box--lose">
          <div class="draw__qian-type">📜 下下签</div>
          <div class="draw__blessing">虽未中奖，好运常伴左右</div>
        </div>
      {/if}
    </div>
  {:else if lotteryData?.state === "PENDING"}
    <div class="draw__status">⏳ 活动尚未开始</div>
  {:else if lotteryData?.state !== "RUNNING"}
    <div class="draw__status">🎊 活动已结束</div>
  {/if}

  <div class="draw__footer">
    已有 {lotteryData?.participantCount || 0} 人参与
  </div>
</div>

<style>
  .draw {
    position: relative;
    background: linear-gradient(180deg, #fef3e2 0%, #fff8f0 100%);
    border-radius: 16px;
    overflow: hidden;
    padding: 24px 20px;
  }

  .draw__header {
    text-align: center;
    margin-bottom: 16px;
  }

  .draw__title {
    color: #c41e3a;
    font-size: 24px;
    font-weight: bold;
    margin: 0;
    text-shadow: 1px 1px 2px rgba(196, 30, 58, 0.1);
  }

  .draw__subtitle {
    color: #d4763a;
    font-size: 14px;
    margin: 6px 0 0;
  }

  .draw__body {
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 16px 0;
  }

  .draw__bucket {
    cursor: pointer;
    outline: none;
    transition: transform 0.2s ease;
  }

  .draw__bucket:hover:not(.draw__bucket--disabled) {
    transform: translateY(-4px);
  }

  .draw__bucket--disabled {
    cursor: default;
    opacity: 0.8;
  }

  .draw__bucket--drawing {
    animation: shake 0.4s ease-in-out infinite;
  }

  @keyframes shake {
    0%, 100% { transform: rotate(0deg) translateY(0); }
    20% { transform: rotate(-5deg) translateY(-2px); }
    40% { transform: rotate(5deg) translateY(-4px); }
    60% { transform: rotate(-5deg) translateY(-2px); }
    80% { transform: rotate(5deg) translateY(-4px); }
  }

  .draw__bucket-img {
    width: 220px;
    height: auto;
    display: block;
    filter: drop-shadow(0 8px 16px rgba(0, 0, 0, 0.15));
  }

  .draw__hint {
    margin: 12px 0 0;
    color: #c41e3a;
    font-size: 14px;
    animation: pulse 2s ease-in-out infinite;
  }

  @keyframes pulse {
    0%, 100% { opacity: 1; }
    50% { opacity: 0.6; }
  }

  .draw__form {
    display: flex;
    flex-direction: column;
    gap: 10px;
    align-items: center;
    margin-top: 16px;
  }

  .draw__form-inputs {
    display: flex;
    flex-direction: column;
    gap: 10px;
    width: 100%;
    max-width: 280px;
  }

  .draw__form input {
    width: 100%;
    padding: 12px 16px;
    border: 2px solid #f0d4a8;
    border-radius: 24px;
    font-size: 14px;
    outline: none;
    background: #fff;
    transition: border-color 0.2s;
  }

  .draw__form input:focus {
    border-color: #c41e3a;
  }

  .draw__code-row {
    display: flex;
    gap: 8px;
  }

  .draw__code-input {
    flex: 1;
    letter-spacing: 4px;
    text-align: center;
  }

  .draw__code-btn {
    flex-shrink: 0;
    padding: 12px 14px;
    font-size: 13px;
    color: #c41e3a;
    background: transparent;
    border: 2px solid #f0d4a8;
    border-radius: 24px;
    cursor: pointer;
    white-space: nowrap;
  }

  .draw__code-btn:disabled {
    color: #a1a1aa;
    border-color: #e4e4e7;
    cursor: not-allowed;
  }

  .draw__error {
    color: #dc2626;
    font-size: 13px;
    text-align: center;
  }

  .draw__form > button {
    padding: 12px 24px;
    background: linear-gradient(180deg, #c41e3a 0%, #a01830 100%);
    color: #fff;
    border: none;
    border-radius: 24px;
    font-size: 14px;
    font-weight: 600;
    cursor: pointer;
    transition: transform 0.2s, box-shadow 0.2s;
    box-shadow: 0 4px 12px rgba(196, 30, 58, 0.3);
  }

  .draw__form button:hover:not(:disabled) {
    transform: translateY(-2px);
    box-shadow: 0 6px 16px rgba(196, 30, 58, 0.4);
  }

  .draw__form button:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }

  .draw__result {
    margin-top: 16px;
  }

  .draw__result-box {
    background: #fff;
    border: 2px solid #f0d4a8;
    border-radius: 12px;
    padding: 14px 20px;
    font-size: 15px;
    color: #666;
    text-align: center;
  }

  .draw__result-box--win {
    background: linear-gradient(135deg, #fff9e6 0%, #ffefc2 100%);
    border-color: #ffd54f;
  }

  .draw__result-box--lose {
    background: linear-gradient(135deg, #fef7f0 0%, #fdf0e6 100%);
    border-color: #f0d4a8;
  }

  .draw__qian-type {
    font-size: 20px;
    font-weight: bold;
    margin-bottom: 8px;
  }

  .draw__result-box--win .draw__qian-type {
    color: #c41e3a;
  }

  .draw__result-box--lose .draw__qian-type {
    color: #d4763a;
  }

  .draw__prize {
    font-size: 15px;
    color: #b45309;
  }

  .draw__blessing {
    font-size: 14px;
    color: #a08060;
  }

  .draw__result-box--error {
    background: #fef2f2;
    border-color: #fecaca;
    color: #dc2626;
  }

  .draw__status {
    text-align: center;
    margin-top: 16px;
    color: #999;
    font-size: 14px;
  }

  .draw__login-hint {
    text-align: center;
    margin-top: 16px;
    color: #d4763a;
    font-size: 14px;
  }

  .draw__footer {
    text-align: center;
    margin-top: 16px;
    padding-top: 12px;
    border-top: 1px solid rgba(0, 0, 0, 0.06);
    color: #999;
    font-size: 13px;
  }
</style>
