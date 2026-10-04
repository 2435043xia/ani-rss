<template>
  <div class="library-item">
    <SettingsItem label="启用">
      <el-switch v-model="notificationConfig.enable"/>
    </SettingsItem>
    <SettingsItem label="备注">
      <el-input v-model="notificationConfig.comment" placeholder="例如：番剧库"/>
    </SettingsItem>
    <SettingsItem label="EmbyHost">
      <el-input v-model="notificationConfig.embyHost" placeholder="http://x.x.x.x:8096"/>
    </SettingsItem>
    <SettingsItem label="Emby密钥">
      <el-input v-model="notificationConfig.embyApiKey" show-password/>
    </SettingsItem>
    <SettingsItem label="路径替换">
      <div>
        <div v-for="(rule, index) in notificationConfig.embyPathReplaceRules" :key="index" class="path-rule">
          <el-input v-model="rule.from" placeholder="Emby 前缀"/>
          <el-input v-model="rule.to" placeholder="ani-rss 前缀"/>
          <el-button text type="danger" @click="notificationConfig.embyPathReplaceRules.splice(index, 1)">删除</el-button>
        </div>
        <el-button text @click="notificationConfig.embyPathReplaceRules.push({from: '', to: ''})">添加规则</el-button>
        <div>
          <el-text size="small">按最长前缀替换一次。只匹配路径开头，末尾的斜杠可以不写。</el-text>
        </div>
      </div>
    </SettingsItem>
    <SettingsItem label="媒体库">
      <div>
        <el-checkbox-group v-model="notificationConfig.embyRefreshViewIds">
          <el-checkbox
              v-for="view in views"
              :key="view.id"
              :label="view.name"
              :value="view.id"/>
        </el-checkbox-group>
        <div>
          <el-button :loading="getEmbyViewsLoading" bg icon="Refresh" text @click="getEmbyViews">
            读取媒体库
          </el-button>
        </div>
        <el-text size="small">先填写地址和密钥，再读取媒体库并勾选要扫描的库。</el-text>
      </div>
    </SettingsItem>
    <SettingsItem label="延迟">
      <el-input-number v-model="notificationConfig.embyDelayed" :min="0" class="notification-input-width">
        <template #suffix>
          <span>秒</span>
        </template>
      </el-input-number>
    </SettingsItem>
    <SettingsItem label="触发">
      <el-checkbox-group v-model="notificationConfig.statusList">
        <el-checkbox label="开始下载" value="DOWNLOAD_START"/>
        <el-checkbox label="下载完成" value="DOWNLOAD_END"/>
        <el-checkbox label="缺集" value="OMIT"/>
        <el-checkbox label="错误" value="ERROR"/>
        <el-checkbox label="订阅完结" value="COMPLETED"/>
        <el-checkbox label="摸鱼检测" value="PROCRASTINATING"/>
      </el-checkbox-group>
    </SettingsItem>
  </div>
</template>

<script setup>
import {onMounted, ref} from "vue";
import {ElMessage} from "element-plus";
import SettingsItem from "@/view/custom/SettingsItem.vue";
import * as http from "@/js/http.js";

const props = defineProps({
  notificationConfig: {
    type: Object,
    required: true
  }
})

const views = ref([])
const getEmbyViewsLoading = ref(false)

const getEmbyViews = () => {
  if (!props.notificationConfig.embyHost || !props.notificationConfig.embyApiKey) {
    ElMessage.warning('请先填写 Emby 地址和密钥')
    return
  }
  getEmbyViewsLoading.value = true
  http.getEmbyViews(props.notificationConfig)
      .then(res => {
        views.value = res.data || []
      })
      .finally(() => {
        getEmbyViewsLoading.value = false
      })
}

onMounted(() => {
  if (!props.notificationConfig.embyRefreshViewIds) {
    props.notificationConfig.embyRefreshViewIds = []
  }
  if (!props.notificationConfig.embyPathReplaceRules) {
    props.notificationConfig.embyPathReplaceRules = []
  }
  if (props.notificationConfig.embyDelayed == null) {
    props.notificationConfig.embyDelayed = 0
  }
  if (props.notificationConfig.embyHost && props.notificationConfig.embyApiKey) {
    getEmbyViews()
  }
})
</script>

<style scoped>
.library-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.path-rule {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
</style>
