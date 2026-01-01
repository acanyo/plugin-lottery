<svelte:options
  customElement={{
    tag: "lottery-card",
    shadow: "none",
    props: {
      name: { reflect: true, type: "String", attribute: "name" },
      theme: { reflect: true, type: "String", attribute: "theme" },
    },
  }}
/>

<script lang="ts">
  import type { LotteryData, ParticipateResult, StatusResult } from "./types";
  import confetti from "canvas-confetti";
  import Scheduled from "./lottery-types/Scheduled.svelte";
  import ScheduledStyle2 from "./lottery-types/ScheduledStyle2.svelte";
  import Wheel from "./lottery-types/Wheel.svelte";
  import Draw from "./lottery-types/Draw.svelte";
  import GridLoading from "./themes/GridLoading.svelte";

  let { name, theme }: { name: string; theme?: string } = $props();

  let loading = $state(false);
  let lotteryData = $state<LotteryData>();
  let participating = $state(false);
  let statusResult = $state<StatusResult>();
  let errorMessage = $state<string>();
  
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
      // 大礼花
      confetti({ particleCount: 100, spread: 70, origin: { y: 0.6 }, colors });
      setTimeout(() => {
        confetti({ particleCount: 50, angle: 60, spread: 55, origin: { x: 0 }, colors });
        confetti({ particleCount: 50, angle: 120, spread: 55, origin: { x: 1 }, colors });
      }, 150);
    } else {
      // 小礼花
      confetti({ particleCount: 50, spread: 60, origin: { y: 0.7 }, colors });
    }
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

  const API_BASE = "http://localhost:8090";

  async function fetchLotteryData() {
    if (!name) return;
    try {
      loading = true;
      errorMessage = undefined;
      const response = await fetch(`${API_BASE}/apis/api.lottery.xhhao.com/v1alpha1/lotteries/${name}`);
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

  async function checkStatus(token: string) {
    try {
      const response = await fetch(`${API_BASE}/apis/api.lottery.xhhao.com/v1alpha1/lotteries/${name}/status?token=${token}`);
      statusResult = (await response.json()) as StatusResult;
    } catch (e) {
      console.error("检查状态失败", e);
    }
  }

  async function participate(email: string, displayName?: string): Promise<ParticipateResult | undefined> {
    if (!name || !email) return undefined;
    try {
      participating = true;
      errorMessage = undefined;
      const response = await fetch(`${API_BASE}/apis/api.lottery.xhhao.com/v1alpha1/lotteries/${name}/participate`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, displayName }),
      });
      const result = (await response.json()) as ParticipateResult;
      
      // 即时开奖类型（大转盘、抽签）由组件自己处理效果
      const isInstantLottery = lotteryData?.lotteryType === "WHEEL" || lotteryData?.lotteryType === "DRAW";
      
      if (result.success && result.token) {
        await storeToken(name, email, result.token);
        statusResult = { participated: true, token: result.token, isWinner: result.isWinner, prizeName: result.prizeName };
        
        // 只有定时开奖才在这里触发 toast 和 confetti
        if (!isInstantLottery) {
          fireConfetti(!!result.isWinner);
          showToastMessage(result.isWinner ? "🎉 恭喜中奖！" : "✨ 参与成功，祝您好运！");
          await fetchLotteryData();
        }
      } else if (!result.success && result.message) {
        // 参与失败
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
      <Wheel {lotteryData} {statusResult} {participating} onParticipate={participate} />
    {:else if lotteryData.lotteryType === "DRAW"}
      <Draw {lotteryData} {statusResult} {participating} onParticipate={participate} />
    {:else}
      <div class="lottery-card">
        {#if theme === "slot-machine" || lotteryData.theme === "slot-machine"}
          <ScheduledStyle2 {lotteryData} {statusResult} {participating} onParticipate={participate} />
        {:else}
          <Scheduled {lotteryData} {statusResult} {participating} onParticipate={participate} />
        {/if}
      </div>
    {/if}
  {:else}
    <div class="lottery-card">
      <div class="lottery-card__empty">活动不存在</div>
    </div>
  {/if}
</div>
