<template>
  <div>
    <el-text size="small">
      在这里配置 Emby。勾选的媒体库会在下方选中的事件发生时扫描。记得点页面右上角的保存。
    </el-text>
    <div v-if="libraries.length === 0" class="library-empty">
      <el-text>还没有 Emby 媒体库。</el-text>
    </div>
    <el-card v-for="(item, index) in libraries" :key="index" class="library-card" shadow="never">
      <template #header>
        <div class="library-card-header">
          <span>{{ item.comment || 'Emby' }}</span>
          <el-button text type="danger" @click="remove(item)">删除</el-button>
        </div>
      </template>
      <EmbyLibraryItem :notification-config="item"/>
    </el-card>
    <el-button :loading="addLoading" class="library-add" icon="Plus" @click="add">
      添加 Emby
    </el-button>
  </div>
</template>

<script setup>
import {computed, ref} from "vue";
import EmbyLibraryItem from "@/view/config/library/EmbyLibraryItem.vue";
import {newNotification} from "@/js/http.js";

const props = defineProps(['config'])

const addLoading = ref(false)

const libraries = computed(() => {
  const list = props.config?.notificationConfigList
  if (!Array.isArray(list)) {
    return []
  }
  return list.filter(item => item.notificationType === 'EMBY_REFRESH')
})

const add = () => {
  addLoading.value = true
  newNotification()
      .then(res => {
        const item = res.data
        item.notificationType = 'EMBY_REFRESH'
        item.comment = 'Emby'
        item.enable = true
        item.embyHost = item.embyHost || ''
        item.embyApiKey = item.embyApiKey || ''
        item.embyRefreshViewIds = item.embyRefreshViewIds || []
        item.embyDelayed = item.embyDelayed || 0
        item.statusList = ['DOWNLOAD_END']
        if (!Array.isArray(props.config.notificationConfigList)) {
          props.config.notificationConfigList = []
        }
        props.config.notificationConfigList.push(item)
      })
      .finally(() => {
        addLoading.value = false
      })
}

const remove = (item) => {
  props.config.notificationConfigList = props.config.notificationConfigList.filter(it => it !== item)
}
</script>

<style scoped>
.library-empty {
  margin-top: 16px;
}

.library-card {
  margin-top: 16px;
}

.library-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.library-add {
  margin-top: 16px;
}
</style>
