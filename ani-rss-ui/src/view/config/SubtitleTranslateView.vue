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
      <SettingsItem label="模型">
        <el-input v-model="props.config.subtitleTranslateGrokModel" placeholder="留空即可"/>
      </SettingsItem>
      <SettingsItem label="思维强度">
        <el-select v-model="props.config.subtitleTranslateReasoningEffort" style="width: 100%">
          <el-option label="不设置" value=""/>
          <el-option label="低" value="low"/>
          <el-option label="中" value="medium"/>
          <el-option label="高" value="high"/>
          <el-option label="很高" value="xhigh"/>
        </el-select>
        <div>可以不选。worker-start 使用 Orca 里 grok 自己保存的模型和强度，不会把这里的值传过去。</div>
      </SettingsItem>
    </template>
    <SettingsItem label="术语表目录">
      <el-input v-model="props.config.subtitleTranslateGlossaryPath"
                placeholder="留空则使用配置目录下的 terminology"/>
    </SettingsItem>
  </div>
</template>

<script setup>
import SettingsItem from "@/view/custom/SettingsItem.vue";

const props = defineProps({
  config: Object
})
</script>
