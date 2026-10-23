"""Exercise the shipped runner with controlled CLI receipts; no SSH or model calls."""
import base64
import contextlib
import io
import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch

RESOURCES = Path(__file__).resolve().parents[2] / "main/resources/orca"


class RunnerTests(unittest.TestCase):
    def setUp(self):
        self.module = types.ModuleType("subtitle_runner_under_test")
        source = (RESOURCES / "subtitle_runner.py").read_text().replace("__ANI_RSS_CATALOG_RPC_B64__",
            base64.b64encode((RESOURCES / "catalog_rpc.cjs").read_bytes()).decode())
        exec(compile(source, "subtitle_runner.py", "exec"), self.module.__dict__)
        self.module.worktree = "name:synthetic-subtitle-test"
        self.module.req = {"agent": "codex", "model": "vendor/model-added-today", "effort": "ultra",
                           "system": "synthetic", "prompt": "1|hello"}
        self.catalog = {"providers": [{"id": "codex", "supportsModelSelection": True, "models": [
            {"id": "vendor/model-added-today", "efforts": [{"value": "low"}, {"value": "ultra"}]}]}]}
        self.commands = []
        self.failed_start = False
        self.structured = True

    def fake_cli(self, command, **kwargs):
        self.commands.append(command)
        verb = command[1:3]
        code = 0
        if verb == ["terminal", "create"]:
            result = {"terminal": {"handle": "term_synthetic123"}}
        elif verb == ["orchestration", "run-create"]:
            result = {"run": {"id": "run_synthetic123"}}
        elif verb == ["orchestration", "worker-start"]:
            if not self.failed_start:
                spec = command[command.index("--spec") + 1]
                output = next(line for line in spec.splitlines() if line.endswith("/out.txt"))
                Path(output).write_text("1|你好\n<terminology>\n</terminology>\n")
            result = {"dispatchId": "ctx_synthetic123", "taskId": "task_synthetic123"}
            code = 1 if self.failed_start else 0
        elif verb == ["orchestration", "worker-show"]:
            result = {"worker": {"state": "failed" if self.failed_start else "succeeded"}}
            if not self.structured: result["terminal"] = {"handle": "term_worker12345"}
        else:
            result = {}
        return types.SimpleNamespace(returncode=code, stdout=json.dumps({"ok": True, "result": result}).encode())

    def run_translation(self):
        output = io.StringIO()
        with patch.object(self.module, "fetch_catalog", return_value=self.catalog) as discovery, \
                patch.object(self.module.subprocess, "run", side_effect=self.fake_cli), \
                contextlib.redirect_stdout(output):
            self.module.translate()
        return output.getvalue(), discovery

    def test_new_opaque_model_and_effort_reach_worker_and_cleanup_own_handles(self):
        output, discovery = self.run_translation()
        launch = next(cmd for cmd in self.commands if cmd[1:3] == ["orchestration", "worker-start"])
        self.assertEqual(launch[launch.index("--agent") + 1], "codex")
        self.assertEqual(launch[launch.index("--model") + 1], "vendor/model-added-today")
        self.assertEqual(launch[launch.index("--effort") + 1], "ultra")
        self.assertIn("1|你好", output)
        discovery.assert_called_once_with("codex", "codex")
        self.assertTrue(any(cmd[1:3] == ["orchestration", "worker-release"] for cmd in self.commands))
        self.assertTrue(any(cmd[1:3] == ["terminal", "close"] and "term_synthetic123" in cmd for cmd in self.commands))
        self.assertFalse(Path(self.module.root).exists())

    def test_legacy_inheritance_has_no_model_override_or_catalog_dependency(self):
        self.module.req = {"agent": "grok", "prompt": "1|hello"}
        _, discovery = self.run_translation()
        discovery.assert_not_called()
        launch = next(cmd for cmd in self.commands if cmd[1:3] == ["orchestration", "worker-start"])
        self.assertNotIn("--model", launch); self.assertNotIn("--effort", launch)

    def test_missing_model_is_refused_before_creating_a_coordinator(self):
        self.module.req["model"] = "removed-model"
        with self.assertRaisesRegex(self.module.OrcaFailure, "selected_model_unavailable"):
            self.run_translation()
        self.assertEqual(self.commands, [])

    def test_wrong_effort_is_refused_instead_of_falling_back(self):
        self.module.req["effort"] = "high"
        with self.assertRaisesRegex(self.module.OrcaFailure, "selected_effort_unavailable"):
            self.run_translation()
        self.assertEqual(self.commands, [])

    def test_inheritance_only_executor_cannot_silently_ignore_an_explicit_model(self):
        self.catalog["providers"][0]["supportsModelSelection"] = False
        with self.assertRaisesRegex(self.module.OrcaFailure, "agent_model_selection_unavailable"):
            self.run_translation()
        self.assertEqual(self.commands, [])

    def test_effort_requires_an_explicit_model(self):
        with self.assertRaisesRegex(self.module.OrcaFailure, "effort_requires_model"):
            self.module.validate_selection("codex", "", "low", self.catalog)

    def test_a_nonzero_start_receipt_still_releases_its_failed_dispatch(self):
        self.failed_start = True
        with self.assertRaises(SystemExit): self.run_translation()
        self.assertTrue(any(cmd[1:3] == ["orchestration", "worker-release"] and "ctx_synthetic123" in cmd for cmd in self.commands))
        self.assertTrue(any(cmd[1:3] == ["terminal", "close"] and "term_synthetic123" in cmd for cmd in self.commands))
        self.assertFalse(Path(self.module.root).exists())

    def test_terminal_workers_close_only_the_returned_worker_and_coordinator(self):
        self.structured = False
        self.run_translation()
        closed = [cmd[cmd.index("--terminal") + 1] for cmd in self.commands if cmd[1:3] == ["terminal", "close"]]
        self.assertEqual(set(closed), {"term_synthetic123", "term_worker12345"})

    def test_catalog_failure_never_starts_a_worker(self):
        with patch.object(self.module, "fetch_catalog", side_effect=self.module.OrcaFailure("orca_catalog_unavailable")), \
                patch.object(self.module.subprocess, "run", side_effect=self.fake_cli):
            with self.assertRaisesRegex(self.module.OrcaFailure, "orca_catalog_unavailable"):
                self.module.translate()
        self.assertEqual(self.commands, [])


if __name__ == "__main__":
    unittest.main()
