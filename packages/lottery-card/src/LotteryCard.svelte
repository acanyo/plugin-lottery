<svelte:options
  customElement={{
    tag: "lottery-card",
    shadow: "none",
    props: {
      name: { reflect: true, type: "String", attribute: "name" },
      theme: { reflect: false, type: "String", attribute: "theme" },
      apiBase: { reflect: false, type: "String", attribute: "api-base" },
      contentName: { reflect: false, type: "String", attribute: "content-name" },
      contentType: { reflect: false, type: "String", attribute: "content-type" },
    },
  }}
/>

<script lang="ts">
  import type { LotteryData, ParticipateResult, StatusResult, ParticipationType, SendCodeResult, VerificationEnabledResult } from "./types";
  import confetti from "canvas-confetti";
  import Scheduled from "./lottery-types/Scheduled.svelte";
  import ScheduledStyle2 from "./lottery-types/ScheduledStyle2.svelte";
  import Wheel from "./lottery-types/Wheel.svelte";
  import Draw from "./lottery-types/Draw.svelte";
  import GridLoading from "./themes/GridLoading.svelte";

  let { 
    name, 
    theme, 
    apiBase = "", 
    contentName = "",
    contentType = ""
  }: { 
    name: string; 
    theme?: string; 
    apiBase?: string; 
    contentName?: string;
    contentType?: string;
  } = $props();

  let loading = $state(false);
  let lotteryData = $state<LotteryData>();
  let participating = $state(false);
  let statusResult = $state<StatusResult>();
  let errorMessage = $state<string>();
  let currentPostName = $state<string>("");
  let commentEmail = $state<string>(""); // 从 localStorage 读取的评论邮箱
  let verificationEnabled = $state(false); // 是否启用邮箱验证
  
  // Toast 状态
  let showToast = $state(false);
  let toastMessage = $state("");

  function showToastMessage(message: string) {
    toastMessage = message;
    showToast = true;
    setTimeout(() => { showToast = false; }, 3000);
  }

  // 礼花效果
  function fireConfetti(isWinner: boolean) {
    const colors = ["#f43f5e", "#fbbf24", "#22c55e", "#3b82f6", "#a855f7"];
    if (isWinner) {
      confetti({ particleCount: 100, spread: 70, origin: { y: 0.6 }, colors });
      setTimeout(() => {
        confetti({ particleCount: 50, angle: 60, spread: 55, origin: { x: 0 }, colors });
        confetti({ particleCount: 50, angle: 120, spread: 55, origin: { x: 1 }, colors });
      }, 150);
    } else {
      confetti({ particleCount: 50, spread: 60, origin: { y: 0.7 }, colors });
    }
  }

  // 获取当前文章/页面的 name
  function detectCurrentPost(): string {
    // 1. 优先使用组件属性传入的（编辑器插入时自动设置）
    if (contentType === "post" && contentName) {
      return contentName;
    }
    
    // 2. 前台页面：尝试从 URL 获取 slug 并查询（这里简化处理，实际可能需要调 API）
    // 由于评论只能关联文章，页面类型直接返回空
    if (contentType === "page") {
      return "";
    }
    
    // 3. 尝试从页面 meta 标签获取
    const metaPost = document.querySelector('meta[name="halo:post-name"]')?.getAttribute('content');
    if (metaPost) return metaPost;
    
    return "";
  }

  // 从 localStorage 读取评论者邮箱（Halo 评论组件会存储）
  function getCommentEmailFromStorage(): string {
    try {
      // Halo 评论组件存储的 key: halo-comment-custom-account
      const data = localStorage.getItem('halo-comment-custom-account');
      if (data) {
        const parsed = JSON.parse(data);
        return parsed.email || "";
      }
    } catch (e) {
      console.error("读取评论信息失败", e);
    }
    return "";
  }

  async function getStoredToken(activityName: string): Promise<string | null> {
    return new Promise((resolve) => {
      const request = indexedDB.open("lottery-tokens", 1);
      request.onerror = () => resolve(null);
      request.onupgradeneeded = (event) => {
        const db = (event.target as IDBOpenDBRequest).result;
        if (!db.objectStoreNames.contains("tokens")) {
          db.createObjectStore("tokens", { keyPath: "activityName" });
        }
      };
      request.onsuccess = () => {
        const db = (request as IDBOpenDBRequest).result;
        const tx = db.transaction("tokens", "readonly");
        const store = tx.objectStore("tokens");
        const getRequest = store.get(activityName);
        getRequest.onsuccess = () => resolve(getRequest.result?.token || null);
        getRequest.onerror = () => resolve(null);
      };
    });
  }

  async function storeToken(activityName: string, email: string, token: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open("lottery-tokens", 1);
      request.onerror = () => reject(new Error("无法打开数据库"));
      request.onupgradeneeded = (event) => {
        const db = (event.target as IDBOpenDBRequest).result;
        if (!db.objectStoreNames.contains("tokens")) {
          db.createObjectStore("tokens", { keyPath: "activityName" });
        }
      };
      request.onsuccess = () => {
        const db = (request as IDBOpenDBRequest).result;
        const tx = db.transaction("tokens", "readwrite");
        const store = tx.objectStore("tokens");
        store.put({ activityName, email, token });
        tx.oncomplete = () => resolve();
        tx.onerror = () => reject(new Error("存储失败"));
      };
    });
  }

  const getApiBase = () => apiBase || "";

  // 根据参与类型获取对应的 API 端点
  function getParticipateEndpoint(participationType: ParticipationType): string {
    const base = `${getApiBase()}/apis/api.lottery.xhhao.com/v1alpha1/lotteries/${name}`;
    switch (participationType) {
      case "LOGIN":
        return `${base}/participate-login`;
      case "COMMENT":
        return `${base}/participate-comment`;
      case "LOGIN_AND_COMMENT":
        return `${base}/participate-login-comment`;
      default:
        return `${base}/participate`;
    }
  }

  async function fetchLotteryData() {
    if (!name) return;
    try {
      loading = true;
      errorMessage = undefined;
      
      // 检测当前文章
      currentPostName = detectCurrentPost();
      
      // 读取评论者邮箱
      commentEmail = getCommentEmailFromStorage();
      
      // 检查是否启用邮箱验证
      await checkVerificationEnabled();
      
      const response = await fetch(`${getApiBase()}/apis/api.lottery.xhhao.com/v1alpha1/lotteries/${name}`);
      if (!response.ok) throw new Error("活动不存在");
      lotteryData = (await response.json()) as LotteryData;
      const storedToken = await getStoredToken(name);
      if (storedToken) await checkStatus(storedToken);
    } catch (e) {
      errorMessage = e instanceof Error ? e.message : "加载失败";
    } finally {
      loading = false;
    }
  }

  async function checkVerificationEnabled() {
    try {
      const response = await fetch(`${getApiBase()}/apis/api.lottery.xhhao.com/v1alpha1/lotteries/settings`);
      if (response.ok) {
        const result = await response.json();
        verificationEnabled = result.verification?.enableEmailVerification === true;
      }
    } catch (e) {
      console.error("获取设置失败", e);
    }
  }

  async function sendVerificationCode(email: string): Promise<SendCodeResult> {
    try {
      const response = await fetch(`${getApiBase()}/apis/api.lottery.xhhao.com/v1alpha1/lotteries/${name}/send-code`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email }),
      });
      return (await response.json()) as SendCodeResult;
    } catch (e) {
      return { success: false, message: e instanceof Error ? e.message : "发送失败" };
    }
  }

  async function checkStatus(token: string) {
    try {
      const response = await fetch(`${getApiBase()}/apis/api.lottery.xhhao.com/v1alpha1/lotteries/${name}/status?token=${token}`);
      statusResult = (await response.json()) as StatusResult;
    } catch (e) {
      console.error("检查状态失败", e);
    }
  }

  async function participate(email: string, displayName?: string, verificationCode?: string): Promise<ParticipateResult | undefined> {
    if (!name || !lotteryData) return undefined;
    
    const participationType = lotteryData.participationType || "NONE";
    
    // COMMENT 类型：优先使用传入的 email，否则使用 localStorage 中的
    // NONE 类型：必须传入 email
    // LOGIN/LOGIN_AND_COMMENT：不需要 email
    let finalEmail = email;
    if (participationType === "COMMENT" && !finalEmail) {
      finalEmail = commentEmail;
    }
    
    // 只有 NONE 类型必须有邮箱，COMMENT 类型可以没有（登录用户）
    if (participationType === "NONE" && !finalEmail) {
      return undefined;
    }
    
    try {
      participating = true;
      errorMessage = undefined;
      
      const endpoint = getParticipateEndpoint(participationType);
      
      // 构建请求体
      const bodyData: Record<string, string | undefined> = {};
      // NONE 类型必须传邮箱，COMMENT 类型有邮箱就传（匿名评论验证）
      if (participationType === "NONE" || (participationType === "COMMENT" && finalEmail)) {
        bodyData.email = finalEmail;
        bodyData.displayName = displayName;
      }
      // 评论相关类型需要传当前文章 name
      if (participationType === "COMMENT" || participationType === "LOGIN_AND_COMMENT") {
        bodyData.postName = currentPostName;
      }
      // 验证码
      if (verificationCode) {
        bodyData.verificationCode = verificationCode;
      }
      
      const response = await fetch(endpoint, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: Object.keys(bodyData).length > 0 ? JSON.stringify(bodyData) : undefined,
      });
      const result = (await response.json()) as ParticipateResult;
      
      const isInstantLottery = lotteryData?.lotteryType === "WHEEL" || lotteryData?.lotteryType === "DRAW";
      
      if (result.success && result.token) {
        await storeToken(name, finalEmail || "logged-in-user", result.token);
        statusResult = { participated: true, token: result.token, isWinner: result.isWinner, prizeName: result.prizeName };
        
        if (!isInstantLottery) {
          fireConfetti(!!result.isWinner);
          showToastMessage(result.isWinner ? "🎉 恭喜中奖！" : "✨ 参与成功，祝您好运！");
          await fetchLotteryData();
        }
      } else if (!result.success && result.message) {
        showToastMessage(result.message);
        if (result.message.includes("已参与")) {
          statusResult = { participated: true };
        }
      }
      return result;
    } catch (e) {
      const msg = e instanceof Error ? e.message : "参与失败";
      errorMessage = msg;
      showToastMessage(msg);
      return { success: false, message: msg };
    } finally {
      participating = false;
    }
  }

  $effect(() => { fetchLotteryData(); });
</script>

<div class="lottery-card-wrapper">
  <!-- Toast 提示 -->
  {#if showToast}
    <div class="lottery-toast">{toastMessage}</div>
  {/if}

  {#if loading}
    <GridLoading />
  {:else if errorMessage}
    <div class="lottery-card">
      <div class="lottery-card__error">{errorMessage}</div>
    </div>
  {:else if lotteryData}
    {#if lotteryData.lotteryType === "WHEEL"}
      <Wheel {lotteryData} {statusResult} {participating} onParticipate={participate} {verificationEnabled} onSendCode={sendVerificationCode} {commentEmail} />
    {:else if lotteryData.lotteryType === "DRAW"}
      <Draw {lotteryData} {statusResult} {participating} onParticipate={participate} {verificationEnabled} onSendCode={sendVerificationCode} {commentEmail} />
    {:else}
      <div class="lottery-card">
        {#if theme === "slot-machine" || lotteryData.theme === "slot-machine"}
          <ScheduledStyle2 {lotteryData} {statusResult} {participating} onParticipate={participate} />
        {:else}
          <Scheduled {lotteryData} {statusResult} {participating} onParticipate={participate} {verificationEnabled} onSendCode={sendVerificationCode} {commentEmail} />
        {/if}
      </div>
    {/if}
  {:else}
    <div class="lottery-card">
      <div class="lottery-card__empty">活动不存在</div>
    </div>
  {/if}
</div>
