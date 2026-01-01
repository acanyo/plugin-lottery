<script lang="ts">
  import loadingSvg from "../assets/loading.svg";
  
  // 吉利话随机显示
  const luckyWords = [
    "好运正在加载中...",
    "幸运即将降临...",
    "惊喜马上揭晓...",
    "福气满满加载中...",
    "好礼等你来拿...",
    "精彩即将呈现...",
  ];
  
  const word = luckyWords[Math.floor(Math.random() * luckyWords.length)];
</script>

<div class="skeleton">
  <img src={loadingSvg} alt="" class="skeleton__icon" />
  <div class="skeleton__text">{word}</div>
  <div class="skeleton__dots">
    <span></span>
    <span></span>
    <span></span>
  </div>
</div>

<style>
  .skeleton {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 16px;
    min-height: 280px;
    background: #fafafa;
    border-radius: 16px;
    padding: 40px 20px;
  }

  .skeleton__icon {
    width: 56px;
    height: 56px;
    animation: bounce 1.5s ease-in-out infinite;
  }

  @keyframes bounce {
    0%, 100% { transform: translateY(0); }
    50% { transform: translateY(-8px); }
  }

  .skeleton__text {
    font-size: 14px;
    color: #999;
  }

  .skeleton__dots {
    display: flex;
    gap: 6px;
  }

  .skeleton__dots span {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: #ddd;
    animation: pulse 1.4s ease-in-out infinite;
  }

  .skeleton__dots span:nth-child(2) {
    animation-delay: 0.2s;
  }

  .skeleton__dots span:nth-child(3) {
    animation-delay: 0.4s;
  }

  @keyframes pulse {
    0%, 100% { opacity: 0.4; }
    50% { opacity: 1; }
  }
</style>
