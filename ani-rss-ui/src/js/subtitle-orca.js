const connectionFields = ["subtitleTranslateSshHost", "subtitleTranslateSshPort", "subtitleTranslateSshUser",
  "subtitleTranslateSshKeyPath", "subtitleTranslateOrcaBin"];

export function orcaConnection(config) {
  return Object.fromEntries(connectionFields.map(key => [key, config[key] ?? ""]));
}

export function orcaConnectionKey(config) { return JSON.stringify(orcaConnection(config)); }

export function orcaProvider(catalog, agent) {
  return catalog?.providers?.find(provider => provider.id === agent);
}

export function orcaModel(catalog, agent, model) {
  return orcaProvider(catalog, agent)?.models?.find(row => row.id === model);
}

export function orcaSelectionMessage(catalog, agent, model, effort) {
  if (!catalog) return "请刷新模型，读取执行端当前目录。";
  const provider = orcaProvider(catalog, agent);
  if (!provider) return "当前执行器未出现在本次目录中。已保存的设置保留，请检查连接后刷新。";
  if (!model) return effort ? "选择思维强度前请先选择模型。" : "";
  if (!provider.supportsModelSelection) return "该执行器当前只支持继承 Orca 设置，请清空模型和强度，或选择其他执行器。";
  const selected = orcaModel(catalog, agent, model);
  if (!selected) return "已保存的模型不在当前目录中，请重新选择；不会自动改用其他模型。";
  if (effort && !selected.efforts?.some(row => row.value === effort)) return "该模型当前不支持已保存的强度，请重新选择。";
  return "";
}
