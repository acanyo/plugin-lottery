<script lang="ts">
  import type { LotteryData, StatusResult, ParticipateResult, Prize, ParticipationType, SendCodeResult } from "../types";
  import confetti from "canvas-confetti";

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
  let spinning = $state(false);
  let sendingCode = $state(false);
  let countdown = $state(0);
  let rotation = $state(0);
  let result = $state<ParticipateResult>();
  let canvasEl = $state<HTMLCanvasElement>();
  let errorMsg = $state("");

  // 真实奖品
  let realPrizes = $derived((lotteryData?.prizes || []) as Prize[]);
  // 谢谢参与格子数量
  let thankYouSlots = $derived(lotteryData?.thankYouSlots ?? 2);
  
  // 参与类型提示文案
  const participationHint: Record<ParticipationType, string> = {
    NONE: "输入邮箱参与抽奖",
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
  
  // 合并后的所有格子（奖品 + 谢谢参与交替排列）
  interface WheelSlot {
    name: string;
    isThankYou: boolean;
    prizeIndex?: number;
  }
  
  let wheelSlots = $derived((): WheelSlot[] => {
    const slots: WheelSlot[] = [];
    const prizeCount = realPrizes.length;
    const totalSlots = prizeCount + thankYouSlots;
    
    if (prizeCount === 0) {
      for (let i = 0; i < Math.max(thankYouSlots, 4); i++) {
        slots.push({ name: "谢谢参与", isThankYou: true });
      }
      return slots;
    }
    
    // 交替排列奖品和谢谢参与
    const thankYouPositions = new Set<number>();
    const step = Math.floor(totalSlots / thankYouSlots);
    for (let i = 0; i < thankYouSlots; i++) {
      thankYouPositions.add((i * step + 1) % totalSlots);
    }
    
    let prizeIdx = 0;
    for (let i = 0; i < totalSlots; i++) {
      if (thankYouPositions.has(i)) {
        slots.push({ name: "谢谢参与", isThankYou: true });
      } else {
        slots.push({ 
          name: realPrizes[prizeIdx]?.name || "奖品", 
          isThankYou: false,
          prizeIndex: prizeIdx 
        });
        prizeIdx++;
      }
    }
    return slots;
  });

  let canParticipate = $derived(
    lotteryData?.state === "RUNNING" && !statusResult?.participated
  );

  function drawWheel() {
    if (!canvasEl) return;
    const slots = wheelSlots();
    if (slots.length === 0) return;
    
    const ctx = canvasEl.getContext("2d");
    if (!ctx) return;

    const size = 280;
    const dpr = window.devicePixelRatio || 1;
    canvasEl.width = size * dpr;
    canvasEl.height = size * dpr;
    canvasEl.style.width = size + "px";
    canvasEl.style.height = size + "px";
    ctx.scale(dpr, dpr);

    const cx = size / 2;
    const cy = size / 2;
    const outerRadius = size / 2 - 2;
    const innerRadius = outerRadius - 16;
    const sliceAngle = (2 * Math.PI) / slots.length;

    // 金色外圈
    ctx.beginPath();
    ctx.arc(cx, cy, outerRadius, 0, Math.PI * 2);
    const gradient = ctx.createLinearGradient(0, 0, size, size);
    gradient.addColorStop(0, "#fbbf24");
    gradient.addColorStop(0.5, "#fcd34d");
    gradient.addColorStop(1, "#f59e0b");
    ctx.fillStyle = gradient;
    ctx.fill();

    // 外圈小圆点装饰
    const dotCount = 24;
    for (let i = 0; i < dotCount; i++) {
      const angle = (Math.PI * 2 / dotCount) * i;
      const dotX = cx + Math.cos(angle) * (outerRadius - 8);
      const dotY = cy + Math.sin(angle) * (outerRadius - 8);
      ctx.beginPath();
      ctx.arc(dotX, dotY, 4, 0, Math.PI * 2);
      ctx.fillStyle = "#fff";
      ctx.fill();
    }

    // 画扇形
    slots.forEach((slot: WheelSlot, i: number) => {
      const startAngle = sliceAngle * i - Math.PI / 2;
      const endAngle = startAngle + sliceAngle;

      ctx.beginPath();
      ctx.moveTo(cx, cy);
      ctx.arc(cx, cy, innerRadius - 2, startAngle, endAngle);
      ctx.closePath();
      
      // 谢谢参与用浅灰色，奖品用白色/米色交替
      if (slot.isThankYou) {
        ctx.fillStyle = "#f5f5f5";
      } else {
        ctx.fillStyle = i % 2 === 0 ? "#fff9e6" : "#ffffff";
      }
      ctx.fill();

      ctx.strokeStyle = "rgba(251, 191, 36, 0.3)";
      ctx.lineWidth = 1;
      ctx.stroke();
    });

    // 画文字
    ctx.font = "bold 12px sans-serif";
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";

    slots.forEach((slot: WheelSlot, i: number) => {
      const angle = sliceAngle * i + sliceAngle / 2 - Math.PI / 2;
      const textRadius = innerRadius * 0.55;
      const x = cx + Math.cos(angle) * textRadius;
      const y = cy + Math.sin(angle) * textRadius;

      ctx.save();
      ctx.translate(x, y);
      const textAngle = angle + Math.PI / 2;
      if (angle > 0 && angle < Math.PI) {
        ctx.rotate(textAngle + Math.PI);
      } else {
        ctx.rotate(textAngle);
      }
      
      ctx.fillStyle = slot.isThankYou ? "#999" : "#92400e";
      const text = slot.name.slice(0, 5);
      ctx.fillText(text, 0, 0);
      ctx.restore();
    });
  }

  $effect(() => {
    setTimeout(drawWheel, 50);
  });

  function fireConfetti() {
    const colors = ["#f43f5e", "#fbbf24", "#22c55e", "#3b82f6", "#a855f7"];
    confetti({ particleCount: 100, spread: 70, origin: { y: 0.6 }, colors });
    setTimeout(() => {
      confetti({ particleCount: 50, angle: 60, spread: 55, origin: { x: 0 }, colors });
      confetti({ particleCount: 50, angle: 120, spread: 55, origin: { x: 1 }, colors });
    }, 150);
  }

  async function handleSpin() {
    if (spinning || !onParticipate) return;
    // NONE 类型必须填写邮箱，COMMENT 类型使用评论邮箱
    const targetEmail = isCommentType ? commentEmail : email;
    if (needsEmail && !email) return;
    // 需要验证码时必须填写
    if (needsVerification && !verificationCode) {
      errorMsg = "请输入验证码";
      return;
    }
    
    spinning = true;
    result = undefined;
    errorMsg = "";

    const apiResult = await onParticipate(targetEmail, undefined, needsVerification ? verificationCode : undefined);
    if (!apiResult?.success) {
      spinning = false;
      result = apiResult;
      errorMsg = apiResult?.message || "";
      return;
    }
    // 参与成功后清除验证码和倒计时
    verificationCode = "";
    countdown = 0;

    const slots = wheelSlots();
    const sliceAngle = 360 / slots.length;
    const baseRotation = 360 * 5;
    let targetSlotIndex = 0;
    
    if (apiResult.isWinner && apiResult.prizeName) {
      // 中奖：找到对应奖品的格子
      targetSlotIndex = slots.findIndex((s: WheelSlot) => !s.isThankYou && s.name === apiResult.prizeName);
      if (targetSlotIndex < 0) targetSlotIndex = slots.findIndex((s: WheelSlot) => !s.isThankYou);
    } else {
      // 未中奖：随机选一个"谢谢参与"格子
      const thankYouIndices = slots.map((s: WheelSlot, i: number) => s.isThankYou ? i : -1).filter((i: number) => i >= 0);
      if (thankYouIndices.length > 0) {
        targetSlotIndex = thankYouIndices[Math.floor(Math.random() * thankYouIndices.length)];
      }
    }

    // 计算角度：让目标格子转到顶部（指针位置）
    const targetAngle = 360 - (targetSlotIndex * sliceAngle + sliceAngle / 2);
    rotation = baseRotation + targetAngle;
    
    setTimeout(() => { 
      spinning = false; 
      result = apiResult;
      if (apiResult?.isWinner) fireConfetti();
    }, 4000);
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
    handleSpin(); 
  }

  function handleCenterClick() {
    if (canParticipate && !spinning) {
      if (needsForm) {
        // 需要表单：NONE 类型需要邮箱，COMMENT 类型需要验证码
        if (needsEmail && email && (!needsVerification || verificationCode)) {
          handleSpin();
        } else if (isCommentType && commentEmail && (!needsVerification || verificationCode)) {
          handleSpin();
        } else {
          showEmailInput = true;
        }
      } else {
        // 不需要表单（登录类型）：直接抽奖
        handleSpin();
      }
    }
  }
</script>

<div class="lottery-wheel lottery-shine">
  <div class="lottery-wheel__wrap">
    <!-- 转盘 -->
    <div class="lottery-wheel__disk" style="transform: rotate({rotation}deg);">
      <canvas bind:this={canvasEl}></canvas>
    </div>
    
    <!-- 中心指针+按钮 -->
    <div class="lottery-wheel__center-wrap">
      <div class="lottery-wheel__pointer"></div>
      <button 
        type="button" 
        class="lottery-wheel__center" 
        onclick={handleCenterClick}
        disabled={!canParticipate || spinning}
      >
        {#if spinning}
          抽奖中
        {:else if statusResult?.participated}
          已抽
        {:else}
          立即<br>抽奖
        {/if}
      </button>
    </div>
  </div>

  <!-- 输入表单 -->
  {#if showEmailInput && canParticipate && needsForm}
    {#if isCommentType && !commentEmail}
      <div class="lottery-wheel__hint lottery-wheel__hint--warning">请先在本文评论，刷新页面后参与</div>
    {:else}
      <form onsubmit={handleEmailSubmit} class="lottery-wheel__form">
        {#if needsEmail}
          <input type="email" bind:value={email} placeholder="输入邮箱参与抽奖" required />
        {/if}
        {#if needsVerification}
          <div class="lottery-wheel__code-row">
            <input type="text" bind:value={verificationCode} placeholder="验证码" required maxlength="6" class="lottery-wheel__code-input" />
            <button type="button" onclick={handleSendCode} disabled={(needsEmail && !email) || (isCommentType && !commentEmail) || sendingCode || countdown > 0} class="lottery-wheel__code-btn">
              {#if sendingCode}发送中{:else if countdown > 0}{countdown}s{:else}获取验证码{/if}
            </button>
          </div>
        {/if}
        {#if errorMsg}
          <div class="lottery-wheel__error">{errorMsg}</div>
        {/if}
        <button type="submit" disabled={(needsEmail && !email) || (needsVerification && !verificationCode)}>确定</button>
      </form>
    {/if}
  {:else if canParticipate && !needsForm && !result}
    <div class="lottery-wheel__hint">{participationHint[lotteryData?.participationType || "LOGIN"]}</div>
  {/if}

  <!-- 结果 -->
  {#if result}
    <div class="lottery-wheel__result">
      {#if result.success && result.isWinner}
        <span class="lottery-wheel__result--win">🎉 恭喜获得「{result.prizeName}」</span>
      {:else if result.success}
        <span>😊 谢谢参与，下次好运！</span>
      {:else}
        <span class="lottery-wheel__result--error">{result.message}</span>
      {/if}
    </div>
  {:else if statusResult?.participated}
    <div class="lottery-wheel__result">
      {#if statusResult.isWinner}
        <span class="lottery-wheel__result--win">🎉 已中奖：{statusResult.prizeName}</span>
      {:else}
        <span>已参与抽奖</span>
      {/if}
    </div>
  {/if}
</div>
