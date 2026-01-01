<script lang="ts">
  import type { LotteryData, StatusResult, ParticipateResult } from "../types";
  import confetti from "canvas-confetti";
  import qiantongSvg from "../assets/qiantong.svg";

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
  let showEmailInput = $state(false);
  let drawing = $state(false);
  let result = $state<ParticipateResult | undefined>();
  let showResult = $state(false);

  let canParticipate = $derived(
    lotteryData?.state === "RUNNING" &&
    !statusResult?.participated &&
    (!lotteryData?.participationType || lotteryData?.participationType === "NONE")
  );

  function fireConfetti() {
    const colors = ["#f43f5e", "#fbbf24", "#22c55e", "#3b82f6", "#a855f7"];
    confetti({ particleCount: 100, spread: 70, origin: { y: 0.6 }, colors });
  }

  async function handleDraw() {
    if (!email || drawing || !onParticipate) return;
    drawing = true;
    result = undefined;
    showResult = false;

    const apiResult = await onParticipate(email, undefined);
    result = apiResult;

    setTimeout(() => {
      drawing = false;
      showResult = true;
      if (apiResult?.isWinner) fireConfetti();
    }, 1500);
  }

  function handleEmailSubmit(e: Event) {
    e.preventDefault();
    if (email) {
      showEmailInput = false;
      handleDraw();
    }
  }

  function handleClick() {
    if (canParticipate && !drawing && !showResult) {
      if (email) handleDraw();
      else showEmailInput = true;
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
      <img src={qiantongSvg} alt="签筒" class="draw__bucket-img" />
    </div>
    
    {#if canParticipate && !drawing && !showResult && !showEmailInput}
      <p class="draw__hint">👆 点击签筒抽签</p>
    {/if}
  </div>

  {#if showEmailInput && canParticipate}
    <form onsubmit={handleEmailSubmit} class="draw__form">
      <input type="email" bind:value={email} placeholder="输入邮箱参与抽签" required />
      <button type="submit" disabled={!email}>开始抽签</button>
    </form>
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
    gap: 10px;
    justify-content: center;
    margin-top: 16px;
  }

  .draw__form input {
    flex: 1;
    max-width: 180px;
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

  .draw__form button {
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

  .draw__footer {
    text-align: center;
    margin-top: 16px;
    padding-top: 12px;
    border-top: 1px solid rgba(0, 0, 0, 0.06);
    color: #999;
    font-size: 13px;
  }
</style>
