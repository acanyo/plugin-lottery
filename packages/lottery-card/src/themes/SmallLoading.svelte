<script lang="ts">
</script>

<div class="w-full p-3 flex items-center gap-3 animate-pulse">
  <!-- 图片骨架 -->
  <div class="w-12 h-12 flex-shrink-0 bg-skeleton rounded-lg"></div>

  <!-- 信息骨架 -->
  <div class="flex-1 min-w-0 space-y-1">
    <div class="h-4 bg-skeleton rounded w-3/4"></div>
    <div class="h-3 bg-skeleton rounded w-1/2"></div>
  </div>

  <!-- 状态骨架 -->
  <div class="h-5 w-14 bg-skeleton rounded-full"></div>
</div>

<style>
  :host {
    display: inline-block;
    width: 100%;
  }
</style>
