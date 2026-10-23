"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const {test} = require("node:test");

const source = fs.readFileSync(path.resolve(__dirname, "../../main/resources/orca/catalog_rpc.cjs"), "utf8");
const capability = "synthetic-worker-launch-preferences";

function hostDisabled(responseOnly = false) {
  const data = {refusal: {code: "structured_agent_session_unsupported", details: {reason: "hostDisabled"}}};
  return Object.assign(new Error("structured_agent_session_unsupported"),
    responseOnly ? {response: {error: {data}}} : {data});
}

async function runCatalog(options = {}) {
  let ready = !options.cold;
  let output = "";
  const calls = [];
  const processStub = {argv: ["node", "/fixture"], stdout: {write: value => { output += value; }}};
  const agents = options.agents ?? ["codex", "claude"];
  class RuntimeClient {
    async call(method, params) {
      calls.push({method, params});
      if (method === "status.get") return {result: {capabilities: [capability]}};
      if (method === "agentSession.agents") return {result: {agents: agents.map(agent => ({agent}))}};
      if (method === "aiVault.listSessions") {
        assert.deepEqual(JSON.parse(JSON.stringify(params)), {limit: 1});
        await new Promise(resolve => setImmediate(resolve));
        if (options.initializationError) throw options.initializationError;
        ready = !options.remainCold;
        return {result: {sessions: []}};
      }
      assert.equal(method, "agentSession.modelCatalog");
      if (options.catalogError?.[params.agent]) throw options.catalogError[params.agent];
      if (!ready) throw hostDisabled(options.responseOnly);
      return {result: {origin: "fixture", models: [{
        id: "vendor/model-added-today", label: "Dynamic model", efforts: [{value: "low", label: "Low"}]
      }]}};
    }
  }
  function fixtureRequire(name) {
    if (name === "node:fs") return {readFileSync: () => JSON.stringify(options.input ?? {})};
    if (name === "/fixture/cli/runtime-client.js") return {RuntimeClient};
    if (name === "/fixture/shared/protocol-version.js") return {
      ORCHESTRATION_WORKER_LAUNCH_PREFERENCES_RUNTIME_CAPABILITY: capability
    };
    if (name === "/fixture/shared/agent-session-option-launch.js") return {
      getAgentSessionOptionLaunchCatalog: () => ({supportsWorkerLaunchPreferences: true})
    };
    if (name === "/fixture/shared/agent-launch-model-preferences.js") return {
      checkAgentLaunchModelPreferences: () => ({refusal: null})
    };
    throw new Error("Unexpected module: " + name);
  }
  await vm.runInNewContext(source, {require: fixtureRequire, process: processStub}, {
    filename: "catalog_rpc.cjs", timeout: 1000
  });
  return {value: JSON.parse(output), calls, exitCode: processStub.exitCode};
}

const count = (run, method, agent) => run.calls.filter(call => call.method === method
  && (agent === undefined || call.params.agent === agent)).length;

test("an initialized host reads models without an initialization request", async () => {
  const run = await runCatalog();
  assert.equal(count(run, "aiVault.listSessions"), 0);
  assert.equal(count(run, "agentSession.modelCatalog"), 2);
  assert.equal(run.value.providers.length, 2);
  assert.equal(run.value.providers[0].models[0].id, "vendor/model-added-today");
  assert.deepEqual(run.value.errors, []);
});

test("concurrent cold agents share one initialization and retry their catalogs once", async () => {
  const run = await runCatalog({cold: true});
  assert.equal(count(run, "aiVault.listSessions"), 1);
  assert.equal(count(run, "agentSession.modelCatalog", "codex"), 2);
  assert.equal(count(run, "agentSession.modelCatalog", "claude"), 2);
  assert.equal(run.value.providers.length, 2);
  assert.deepEqual(run.value.errors, []);
});

test("a selected agent retries without querying other registered agents", async () => {
  const run = await runCatalog({cold: true, input: {agent: "codex"}});
  assert.equal(count(run, "aiVault.listSessions"), 1);
  assert.equal(count(run, "agentSession.modelCatalog", "codex"), 2);
  assert.equal(count(run, "agentSession.modelCatalog", "claude"), 0);
  assert.equal(run.value.providers.length, 1);
});

test("the response error envelope also identifies an uninitialized host", async () => {
  const run = await runCatalog({cold: true, responseOnly: true});
  assert.equal(count(run, "aiVault.listSessions"), 1);
  assert.equal(run.value.providers.length, 2);
});

test("a client capability refusal is returned without initialization or retry", async () => {
  const error = Object.assign(new Error("structured_agent_session_unsupported"), {data: {
    refusal: {code: "structured_agent_session_unsupported", details: {reason: "clientCapabilityMissing"}}
  }});
  const run = await runCatalog({agents: ["codex"], catalogError: {codex: error}});
  assert.equal(count(run, "aiVault.listSessions"), 0);
  assert.equal(count(run, "agentSession.modelCatalog"), 1);
  assert.equal(run.value.providers.length, 0);
  assert.equal(run.value.errors.length, 1);
});

test("an unrelated catalog failure keeps healthy agents and does not initialize the host", async () => {
  const run = await runCatalog({catalogError: {codex: new Error("connection refused")}});
  assert.equal(count(run, "aiVault.listSessions"), 0);
  assert.equal(count(run, "agentSession.modelCatalog"), 2);
  assert.deepEqual(run.value.providers.map(p => p.id), ["claude"]);
  assert.equal(run.value.errors.length, 1);
});

test("a failed shared initialization stops recovery without further catalog requests", async () => {
  const run = await runCatalog({cold: true, initializationError: new Error("permission denied")});
  assert.equal(count(run, "aiVault.listSessions"), 1);
  assert.equal(count(run, "agentSession.modelCatalog"), 2);
  assert.equal(run.value.providers.length, 0);
  assert.equal(run.value.errors.length, 2);
});

test("a host that stays unavailable is retried only once", async () => {
  const run = await runCatalog({cold: true, remainCold: true});
  assert.equal(count(run, "aiVault.listSessions"), 1);
  assert.equal(count(run, "agentSession.modelCatalog", "codex"), 2);
  assert.equal(count(run, "agentSession.modelCatalog", "claude"), 2);
  assert.equal(run.value.providers.length, 0);
  assert.equal(run.value.errors.length, 2);
});
