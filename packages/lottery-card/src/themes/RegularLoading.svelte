<script lang="ts">
</script>

<div class="w-full p-4 space-y-4 animate-pulse">
  <!-- 头部骨架 -->
  <div class="flex items-start justify-between gap-3">
    <div class="flex-1 space-y-2">
      <div class="h-6 bg-skeleton rounded w-3/4"></div>
      <div class="h-4 bg-skeleton rounded w-full"></div>
    </div>
    <div class="h-6 w-16 bg-skeleton rounded-full"></div>
  </div>

  <!-- 奖品骨架 -->
  <div class="space-y-2">
    <div class="h-4 bg-skeleton rounded w-12"></div>
    <div class="flex gap-2">
      <div class="h-10 w-24 bg-skeleton rounded-lg"></div>
      <div class="h-10 w-24 bg-skeleton rounded-lg"></div>
    </div>
  </div>

  <!-- 信息骨架 -->
  <div class="grid grid-cols-2 gap-2">
    <div class="h-4 bg-skeleton rounded"></div>
    <div class="h-4 bg-skeleton rounded"></div>
    <div class="h-4 bg-skeleton rounded"></div>
    <div class="h-4 bg-skeleton rounded"></div>
  </div>

  <!-- 按钮骨架 -->
  <div class="pt-2 border-t border-card">
    <div class="h-10 bg-skeleton rounded-lg"></div>
  </div>
</div>

<style>
  :host {
    display: inline-block;
    width: 100%;
  }
</style>
