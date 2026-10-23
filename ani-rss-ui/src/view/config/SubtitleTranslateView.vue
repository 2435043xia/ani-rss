<template>
  <div>
    <SettingsItem label="翻译方式">
      <el-select v-model="props.config.subtitleTranslateBackend" style="width: 100%">
        <el-option label="API" value="api"/>
        <el-option label="SSH（Orca）" value="ssh"/>
      </el-select>
    </SettingsItem>
    <template v-if="props.config.subtitleTranslateBackend !== 'ssh'">
      <SettingsItem label="API 地址">
        <el-input v-model="props.config.subtitleTranslateApiBase" placeholder="https://api.deepseek.com"/>
      </SettingsItem>
      <SettingsItem label="模型">
        <el-input v-model="props.config.subtitleTranslateModel" placeholder="deepseek-chat"/>
      </SettingsItem>
      <SettingsItem label="API Key">
        <el-input v-model="props.config.subtitleTranslateApiKey" show-password placeholder="API Key"/>
      </SettingsItem>
    </template>
    <template v-else>
      <SettingsItem label="SSH 主机">
        <el-input v-model="props.config.subtitleTranslateSshHost" placeholder="主机名或 IP"/>
      </SettingsItem>
      <SettingsItem label="SSH 端口">
        <el-input-number v-model="props.config.subtitleTranslateSshPort" :min="1" :max="65535" :step="1"/>
      </SettingsItem>
      <SettingsItem label="SSH 用户">
        <el-input v-model="props.config.subtitleTranslateSshUser" placeholder="登录用户"/>
      </SettingsItem>
      <SettingsItem label="私钥路径">
        <el-input v-model="props.config.subtitleTranslateSshKeyPath" placeholder="容器内能读到的私钥，例如 /config/id_ed25519"/>
      </SettingsItem>
      <SettingsItem label="Orca 路径">
        <el-input v-model="props.config.subtitleTranslateOrcaBin" placeholder="留空则使用 /usr/local/bin/orca"/>
      </SettingsItem>
      <SettingsItem label="工作区">
        <el-input v-model="props.config.subtitleTranslateOrcaWorktree" placeholder="例如 name:misc"/>
      </SettingsItem>
      <SettingsItem label="读取模型">
        <el-button :loading="loading" :disabled="!canRefresh" @click="refreshModels">刷新模型</el-button>
        <div>从当前 SSH 执行端读取，不创建翻译任务。修改连接后需重新刷新。</div>
        <div v-if="loadError" style="color: var(--el-color-danger)">{{ loadError }}</div>
        <div v-for="message in catalog?.errors || []" :key="message">{{ message }}</div>
      </SettingsItem>
      <SettingsItem label="执行器">
        <el-select v-model="props.config.subtitleTranslateOrcaAgent" :disabled="!catalog" style="width: 100%" @change="changeAgent">
          <el-option v-for="provider in catalog?.providers || []" :key="provider.id"
                     :label="provider.label" :value="provider.id"/>
        </el-select>
        <div>GPT 模型位于 Codex 执行器中；列表以执行端实际返回为准。</div>
      </SettingsItem>
      <SettingsItem label="模型">
        <el-select v-model="props.config.subtitleTranslateOrcaModel" filterable style="width: 100%"
                   :disabled="!selectedProvider?.supportsModelSelection" @change="changeModel">
          <el-option label="继承 Orca 配置" value=""/>
          <el-option v-for="model in selectedProvider?.models || []" :key="model.id"
                     :label="model.label + ' · ' + model.id" :value="model.id"/>
        </el-select>
        <div v-if="selectedProvider?.inheritOnly">当前 Orca 的此执行器只支持继承设置，不提供任务启动时的模型切换。</div>
        <el-button v-if="selectedProvider?.inheritOnly && (props.config.subtitleTranslateOrcaModel || props.config.subtitleTranslateOrcaEffort)"
                   text @click="changeAgent">使用继承设置</el-button>
      </SettingsItem>
      <SettingsItem label="思维强度">
        <el-select v-model="props.config.subtitleTranslateOrcaEffort" style="width: 100%" :disabled="!selectedModel">
          <el-option label="继承模型默认值" value=""/>
          <el-option v-for="effort in selectedModel?.efforts || []" :key="effort.value"
                     :label="effort.label" :value="effort.value"/>
        </el-select>
        <div>只显示所选模型支持的强度。保存后，新的翻译任务使用此设置。</div>
        <div v-if="selectionMessage" style="color: var(--el-color-warning)">{{ selectionMessage }}</div>
      </SettingsItem>
    </template>
    <SettingsItem label="术语表目录">
      <el-input v-model="props.config.subtitleTranslateGlossaryPath"
                placeholder="留空则使用配置目录下的 terminology"/>
    </SettingsItem>
  </div>
</template>

<script setup>
import {computed, ref, watch} from "vue";
import SettingsItem from "@/view/custom/SettingsItem.vue";
import api from "@/js/api.js";
import {orcaConnection, orcaConnectionKey, orcaProvider, orcaModel, orcaSelectionMessage} from "@/js/subtitle-orca.js";

const props = defineProps({config: Object});
const catalog = ref(null), loading = ref(false), loadError = ref("");
let generation = 0;
const canRefresh = computed(() => !!props.config.subtitleTranslateSshHost?.trim()
    && !!props.config.subtitleTranslateSshUser?.trim() && !!props.config.subtitleTranslateSshKeyPath?.trim()
    && props.config.subtitleTranslateSshPort >= 1 && props.config.subtitleTranslateSshPort <= 65535);
const selectedProvider = computed(() => orcaProvider(catalog.value, props.config.subtitleTranslateOrcaAgent));
const selectedModel = computed(() => orcaModel(catalog.value, props.config.subtitleTranslateOrcaAgent,
    props.config.subtitleTranslateOrcaModel));
const selectionMessage = computed(() => orcaSelectionMessage(catalog.value,
    props.config.subtitleTranslateOrcaAgent, props.config.subtitleTranslateOrcaModel, props.config.subtitleTranslateOrcaEffort));
watch(() => orcaConnectionKey(props.config), () => {
  generation++; catalog.value = null; loadError.value = ""; loading.value = false;
});
function changeAgent() {
  props.config.subtitleTranslateOrcaModel = "";
  props.config.subtitleTranslateOrcaEffort = "";
}
function changeModel() { props.config.subtitleTranslateOrcaEffort = ""; }
async function refreshModels() {
  const current = ++generation, key = orcaConnectionKey(props.config);
  loading.value = true; loadError.value = "";
  try {
    const response = await api.post("api/subtitleTranslateOrcaModels", {...orcaConnection(props.config),
      subtitleTranslateOrcaAgent: props.config.subtitleTranslateOrcaAgent}, {silent: true});
    if (current !== generation || key !== orcaConnectionKey(props.config)) return;
    if (!response.data?.ok || !Array.isArray(response.data.providers)) throw new Error("模型目录响应无效。");
    catalog.value = response.data;
    if (!catalog.value.providers.length) loadError.value = "未读到可用执行器和模型，请检查远端 Orca 后刷新。";
  } catch (error) {
    if (current === generation) { catalog.value = null; loadError.value = error.message || "模型读取失败，请稍后刷新。"; }
  } finally { if (current === generation) loading.value = false; }
}
</script>
