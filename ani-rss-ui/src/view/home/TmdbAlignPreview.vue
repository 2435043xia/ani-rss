<template>
  <el-dialog v-model="dialogVisible" align-center center title="TMDB 对齐" width="720">
    <div v-if="preview" class="align-body">
      <div v-if="preview.tmdb" class="align-row">
        <div class="align-label">TMDB</div>
        <div>
          <div>{{ preview.themoviedbName }}</div>
          <div>id {{ preview.tmdb.id }}</div>
        </div>
      </div>

      <div class="align-row">
        <div class="align-label">主 RSS</div>
        <div>
          <div>{{ preview.rssTitle || "没有可用条目" }}</div>
          <div v-if="preview.rssEpisode != null">集号 {{ preview.rssEpisode }}，日期 {{ preview.rssPubDate }}</div>
          <div v-if="preview.rssNote">{{ preview.rssNote }}</div>
        </div>
      </div>

      <div class="align-row">
        <div class="align-label">TMDB 剧集</div>
        <div v-if="!preview.episodeChoices.length">没有对上的集</div>
        <div v-else-if="preview.episodeChoices.length === 1">
          S{{ pad(preview.episodeChoices[0].seasonNumber) }}E{{ pad(preview.episodeChoices[0].episodeNumber) }}
          {{ preview.episodeChoices[0].episodeName }}
          {{ preview.episodeChoices[0].airDate }}
        </div>
        <el-radio-group v-else v-model="episodeIndex" class="align-choices">
          <el-radio v-for="(choice, index) in preview.episodeChoices" :key="index" :value="index">
            S{{ pad(choice.seasonNumber) }}E{{ pad(choice.episodeNumber) }}
            {{ choice.episodeName }}
            {{ choice.airDate }}
          </el-radio>
        </el-radio-group>
      </div>

      <div class="align-row">
        <div class="align-label">季和偏移</div>
        <div>
          <div>当前季 {{ preview.currentSeason }}，偏移 {{ preview.currentOffset }}</div>
          <div v-if="selectedChoice">新季 {{ selectedChoice.newSeason }}，偏移 {{ selectedChoice.newOffset }}</div>
        </div>
      </div>

      <div class="align-row">
        <div class="align-label">示例文件名</div>
        <div>{{ selectedChoice?.sampleFileName || "" }}</div>
      </div>

      <div class="align-row">
        <div class="align-label">重命名模板</div>
        <div>{{ preview.renameTemplate }}</div>
      </div>

      <div class="align-row">
        <div class="align-label">下载路径</div>
        <div v-if="preview.embyChoices.length === 1">
          <div>{{ preview.embyChoices[0].seriesName }}（{{ preview.embyChoices[0].embyHost }}）</div>
          <div>{{ withSeason(preview.embyChoices[0].replacedPath) }}</div>
          <div class="align-muted">原路径 {{ preview.embyChoices[0].originalPath }}</div>
        </div>
        <div v-else-if="preview.embyChoices.length > 1" class="align-choices">
          <el-radio-group v-model="embyIndex">
            <el-radio v-for="(hit, index) in preview.embyChoices" :key="index" :value="index">
              <div>{{ hit.seriesName }}（{{ hit.embyHost }}）</div>
              <div>{{ withSeason(hit.replacedPath) }}</div>
              <div class="align-muted">原路径 {{ hit.originalPath }}</div>
            </el-radio>
          </el-radio-group>
        </div>
        <div v-else>{{ selectedChoice?.fallbackPath || "" }}</div>
      </div>

      <div v-if="selectedChoice?.seasonConflict" class="align-row">
        <div class="align-label">季冲突</div>
        <div>订阅标题重复：{{ selectedChoice.conflictWith }}</div>
      </div>

      <div v-if="preview.blockReason" class="align-row">
        <div class="align-label">不能写入</div>
        <div>{{ preview.blockReason }}</div>
      </div>

      <div v-if="preview.embyMessage" class="align-row">
        <div class="align-label">Emby</div>
        <div>{{ preview.embyMessage }}</div>
      </div>
    </div>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :disabled="!canConfirm" @click="confirm">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import {computed, ref} from "vue";
import * as http from "@/js/http.js";

const props = defineProps(["ani"])
const dialogVisible = ref(false)
const preview = ref(null)
const episodeIndex = ref(null)
const embyIndex = ref(null)

const selectedChoice = computed(() => {
  if (episodeIndex.value == null || !preview.value?.episodeChoices) {
    return null
  }
  return preview.value.episodeChoices[episodeIndex.value] || null
})

const canConfirm = computed(() => {
  if (!preview.value || preview.value.blockReason) {
    return false
  }
  const choice = selectedChoice.value
  if (!choice || choice.seasonConflict) {
    return false
  }
  const embyChoices = preview.value.embyChoices || []
  if (embyChoices.length > 1 && (embyIndex.value == null || !embyChoices[embyIndex.value])) {
    return false
  }
  return true
})

const pad = (value) => String(value ?? "").padStart(2, "0")

const withSeason = (path) => {
  if (!path) {
    return ""
  }
  if (/(?:^|[/\\])Season(?:\s*\d+)?(?:$|[/\\])/i.test(path)) {
    return path
  }
  const folder = selectedChoice.value?.seasonFolder || ""
  if (!folder) {
    return path
  }
  const base = path.replace(/[/\\]+$/, "")
  const suffix = folder.startsWith("/") || folder.startsWith("\\") ? folder : "/" + folder
  return base + suffix
}

const show = () => {
  http.previewTmdbAlign(props.ani)
      .then(res => {
        preview.value = res.data || {}
        preview.value.episodeChoices = preview.value.episodeChoices || []
        preview.value.embyChoices = preview.value.embyChoices || []
        episodeIndex.value = preview.value.episodeChoices.length === 1 ? 0 : null
        embyIndex.value = preview.value.embyChoices.length === 1 ? 0 : null
        dialogVisible.value = true
      })
}

const confirm = () => {
  if (!canConfirm.value) {
    return
  }
  const choice = preview.value.episodeChoices[episodeIndex.value]
  props.ani.season = choice.newSeason
  props.ani.offset = choice.newOffset
  props.ani.customRenameTemplateEnable = true
  props.ani.customRenameTemplate = preview.value.renameTemplate
  props.ani.themoviedbName = preview.value.themoviedbName
  props.ani.customDownloadPath = true
  if (preview.value.embyChoices.length > 0) {
    props.ani.customDownloadPathTemplate = withSeason(preview.value.embyChoices[embyIndex.value].replacedPath)
  } else {
    props.ani.customDownloadPathTemplate = choice.fallbackPath
  }
  if (!props.ani.tmdb?.id && preview.value.tmdb) {
    const groupId = props.ani.tmdb?.tmdbGroupId
    props.ani.tmdb = preview.value.tmdb
    if (groupId) {
      props.ani.tmdb.tmdbGroupId = groupId
    }
  }
  dialogVisible.value = false
}

defineExpose({show})
</script>

<style scoped>
.align-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.align-row {
  display: flex;
  gap: 12px;
}

.align-label {
  width: 96px;
  flex: none;
  color: var(--el-text-color-secondary);
}

.align-choices {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  width: 100%;
}

.align-choices :deep(.el-radio-group) {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  width: 100%;
}

.align-choices :deep(.el-radio) {
  height: auto;
  align-items: flex-start;
  white-space: normal;
  margin-right: 0;
  margin-bottom: 12px;
}

.align-choices :deep(.el-radio__input) {
  margin-top: 4px;
}

.align-choices :deep(.el-radio__label) {
  white-space: normal;
  line-height: 1.5;
  word-break: break-all;
}

.align-muted {
  color: var(--el-text-color-secondary);
}
</style>
