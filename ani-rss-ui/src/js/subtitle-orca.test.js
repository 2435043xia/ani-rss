import test from "node:test";
import assert from "node:assert/strict";
import {orcaConnection, orcaConnectionKey, orcaProvider, orcaModel, orcaSelectionMessage} from "./subtitle-orca.js";

const catalog = {providers: [{id: "codex", supportsModelSelection: true, models: [
  {id: "vendor/new-gpt", efforts: [{value: "low"}, {value: "ultra"}]}]},
  {id: "grok", supportsModelSelection: false, models: []}]};

test("connection requests exclude credentials for unrelated API providers and selection changes", () => {
  const config = {subtitleTranslateSshHost: "host", subtitleTranslateSshPort: 22,
    subtitleTranslateSshUser: "user", subtitleTranslateSshKeyPath: "/key",
    subtitleTranslateApiKey: "never-send", subtitleTranslateOrcaModel: "saved-model"};
  assert.equal(JSON.stringify(orcaConnection(config)).includes("never-send"), false);
  assert.equal(orcaConnectionKey(config), orcaConnectionKey({...config, subtitleTranslateOrcaModel: "different"}));
  assert.notEqual(orcaConnectionKey(config), orcaConnectionKey({...config, subtitleTranslateSshHost: "new-host"}));
});
test("opaque model IDs and newly returned effort levels are used as-is", () => {
  assert.equal(orcaModel(catalog, "codex", "vendor/new-gpt").id, "vendor/new-gpt");
  assert.equal(orcaSelectionMessage(catalog, "codex", "vendor/new-gpt", "ultra"), "");
  assert.equal(orcaProvider(catalog, "unknown"), undefined);
});
test("stale models, unsupported efforts and inheritance-only executors are explicit", () => {
  assert.match(orcaSelectionMessage(catalog, "codex", "gone-model", ""), /重新选择/);
  assert.match(orcaSelectionMessage(catalog, "codex", "vendor/new-gpt", "high"), /强度/);
  assert.match(orcaSelectionMessage(catalog, "grok", "old-model", ""), /继承/);
  assert.equal(orcaSelectionMessage(catalog, "grok", "", ""), "");
});
test("failed or empty discovery does not silently replace a saved selection", () => {
  assert.match(orcaSelectionMessage(null, "codex", "saved", ""), /刷新/);
  assert.match(orcaSelectionMessage({providers: []}, "codex", "saved", ""), /保留/);
  assert.match(orcaSelectionMessage(catalog, "codex", "", "low"), /先选择模型/);
});
