// Runs with the execution host's Orca/Electron installation. Read-only RPCs only.
"use strict";
const fs = require("node:fs");
const base = process.argv[1];
const input = JSON.parse(fs.readFileSync(0, "utf8"));
const {RuntimeClient} = require(base + "/cli/runtime-client.js");
const {getAgentSessionOptionLaunchCatalog} = require(base + "/shared/agent-session-option-launch.js");
const {checkAgentLaunchModelPreferences} = require(base + "/shared/agent-launch-model-preferences.js");
const {ORCHESTRATION_WORKER_LAUNCH_PREFERENCES_RUNTIME_CAPABILITY: launchCapability} = require(base + "/shared/protocol-version.js");
const client = new RuntimeClient(undefined, 35000, null, null);
const agentID = /^[a-z][a-z0-9_-]{0,63}$/;
const modelID = /^[A-Za-z0-9][A-Za-z0-9._:/@-]{0,199}$/;
const effortID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$/;
const label = (value, fallback) => typeof value === "string" && value.trim() ? value.slice(0, 200) : fallback;

async function main() {
  const [status, registered] = await Promise.all([
    client.call("status.get", {}), client.call("agentSession.agents", {})
  ]);
  const canSelect = Array.isArray(status.result.capabilities) && status.result.capabilities.includes(launchCapability);
  const errors = [];
  const agents = (registered.result.agents || []).filter(a => agentID.test(a.agent));
  const groups = await Promise.all(agents.map(async agent => {
    if (input.agent && agent.agent !== input.agent) return null;
    const launch = getAgentSessionOptionLaunchCatalog(agent.agent);
    const supportsModelSelection = canSelect && launch?.supportsWorkerLaunchPreferences === true;
    const title = label(agent.label, agent.agent);
    try {
      const response = await client.call("agentSession.modelCatalog", {agent: agent.agent, waitForListing: true});
      const catalog = response.result || {};
      const rows = (catalog.models || []).filter(row => row && modelID.test(row.id)).slice(0, 500);
      if (!supportsModelSelection) {
        return rows.length ? {id: agent.agent, label: title, supportsModelSelection: false,
          models: [], origin: catalog.origin || "runtime", inheritOnly: true} : null;
      }
      const seen = new Set();
      const models = rows.flatMap(row => {
        if (seen.has(row.id)) return [];
        seen.add(row.id);
        const args = {agent: agent.agent, model: row.id, createsWorktree: false, openCodeModelLaunchSupported: true};
        if (checkAgentLaunchModelPreferences(args).refusal) return [];
        const efforts = (row.efforts || []).filter(e => e && effortID.test(e.value)
          && !checkAgentLaunchModelPreferences({...args, effort: e.value}).refusal)
          .map(e => ({value: e.value, label: label(e.label, e.value)}));
        return [{id: row.id, label: label(row.label, row.id), isDefault: row.isDefault === true, efforts}];
      });
      if (!models.length) {
        return null;
      }
      return {id: agent.agent, label: title, supportsModelSelection: true, inheritOnly: false,
        models, origin: catalog.origin || "runtime"};
    } catch (_) {
      errors.push(title + " 的模型目录读取失败，请稍后刷新。");
      return null;
    }
  }));
  process.stdout.write(JSON.stringify({ok: true, providers: groups.filter(Boolean), errors,
    launchModelSelectionSupported: canSelect, checkedAt: Date.now()}));
}
main().catch(() => {
  process.stdout.write(JSON.stringify({ok: false, error: "orca_catalog_unavailable"}));
  process.exitCode = 1;
});
