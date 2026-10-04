<template>
  <el-alert
      v-if="notificationConfig.notificationType === 'EMBY_REFRESH'"
      :closable="false"
      class="library-hint"
      description="地址、密钥和要扫描的媒体库在「设置 → 媒体库管理」里配置。这里只决定哪些事件会触发扫描。"
      show-icon
      title="媒体库在「媒体库管理」中配置"
      type="info"/>
</template>

<script setup>
defineProps(['notificationConfig', 'config'])
</script>

<style scoped>
.library-hint {
  margin-bottom: 12px;
}
</style>
