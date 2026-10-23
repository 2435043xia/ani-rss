import base64, json, os, re, shutil, signal, subprocess, sys, time, tempfile
from pathlib import Path
req = {}
orca = "/usr/local/bin/orca"
worktree = ""
RPC_SOURCE_B64 = "__ANI_RSS_CATALOG_RPC_B64__"
root = None
state = {"coord": None, "dispatch": None, "run": None, "task": None, "agent": None}

def run_json(args, timeout):
    cmd = [orca]
    cmd.extend(args)
    cmd.append("--json")
    try:
        proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=timeout)
    except Exception:
        return None
    if not proc.stdout:
        return None
    try:
        payload = json.loads(proc.stdout.decode("utf-8", "replace"))
    except Exception:
        return None
    if not payload.get("ok", True):
        return None
    # Keep a failed/uncertain launch receipt so its own dispatch can still be stopped.
    if proc.returncode != 0 and args[:2] != ["orchestration", "worker-start"]:
        return None
    result = payload.get("result")
    return result if isinstance(result, dict) else None

def close_coord():
    handle = state["coord"]
    state["coord"] = None
    if not handle:
        return
    try:
        subprocess.run(
            [orca, "terminal", "close", "--terminal", handle, "--json"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20,
        )
    except Exception:
        pass

def stop_worker():
    dispatch = state["dispatch"]
    if not dispatch:
        return
    try:
        subprocess.run(
            [orca, "orchestration", "worker-stop", "--dispatch", dispatch, "--json"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30,
        )
    except Exception:
        pass

def release_worker():
    dispatch = state["dispatch"]
    if not dispatch:
        return
    try:
        subprocess.run(
            [orca, "orchestration", "worker-release", "--dispatch", dispatch, "--json"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30,
        )
    except Exception:
        pass

def cleanup_root():
    if not root:
        return
    try:
        for name in os.listdir(root):
            try:
                os.remove(os.path.join(root, name))
            except OSError:
                pass
        os.rmdir(root)
    except OSError:
        pass

def close_agent():
    handle = state.get("agent")
    state["agent"] = None
    if not handle or handle == state.get("coord"):
        return
    try:
        subprocess.run(
            [orca, "terminal", "close", "--terminal", handle, "--json"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20,
        )
    except Exception:
        pass

def finish_task(status):
    task_id = state.get("task")
    coord = state.get("coord")
    if not task_id or not coord:
        return
    args = ["orchestration", "task-update", "--id", task_id, "--status", status, "--from", coord]
    if state.get("run"):
        args.extend(["--run", state["run"]])
    run_json(args, 20)

def close_worker(settled):
    if settled:
        release_worker()
    else:
        stop_worker()
    close_agent()

def stop(signum, frame):
    stop_worker()
    close_agent()
    finish_task("failed")
    close_coord()
    cleanup_root()
    sys.exit(1)


def worker_state(show):
    worker = show.get("worker") if isinstance(show, dict) else None
    if not isinstance(worker, dict):
        return ""
    return str(worker.get("state") or "")

def read_out(path):
    try:
        return open(path, encoding="utf-8").read()
    except Exception:
        return ""

def useful(text):
    return re.search(r"(?m)^\d+\|", text or "") is not None

def translate():
    global root
    try:
        if not worktree:
            raise OrcaFailure("worktree_required")
        selected_agent = str(req.get("agent") or "grok").strip()
        selected_model = str(req.get("model") or "").strip()
        selected_effort = str(req.get("effort") or "").strip()
        if not re.fullmatch(r"[a-z][a-z0-9_-]{0,63}", selected_agent):
            raise OrcaFailure("invalid_agent")
        if selected_model or selected_effort:
            validate_selection(selected_agent, selected_model, selected_effort,
                               fetch_catalog(selected_agent, selected_agent))
        created = run_json(["terminal", "create", "--title", "ani-rss-subtitle", "--worktree", worktree], 40)
        handle = ""
        if created:
            term = created.get("terminal")
            if isinstance(term, dict):
                handle = str(term.get("handle") or "")
        if re.fullmatch(r"term_[A-Za-z0-9-]{8,80}", handle) is None:
            sys.exit(1)
        state["coord"] = handle
        created_run = run_json(["orchestration", "run-create", "--from", handle, "--objective", "ani-rss subtitle"], 30)
        run_obj = (created_run or {}).get("run")
        run_id = ""
        if isinstance(run_obj, dict):
            run_id = str(run_obj.get("id") or run_obj.get("runId") or "")
        elif isinstance(run_obj, str):
            run_id = run_obj
        if not run_id and created_run:
            run_id = str(created_run.get("runId") or created_run.get("id") or "")
        if re.fullmatch(r"run_[A-Za-z0-9]{6,80}", run_id) is None:
            sys.exit(1)
        state["run"] = run_id
        root = tempfile.mkdtemp(prefix="ani-rss-orca-")
        out_path = os.path.join(root, "out.txt")
        spec = "\n".join([
            "你在给 ani-rss 翻译字幕。不要搜索网页，不要阅读或修改工作区，不要调用 Unity、MCP 或其他工具。",
            "只把完整译文写入这个绝对路径，覆盖已有文件：",
            out_path,
            "文件里只能有译文。格式沿用下面的说明，每一条都是序号|译文，并以术语块结尾。",
            "写完文件后只发送一次 worker_done，正文只写 wrote。不要搜索网页，不要读仓库。被拒绝时不要改文件，也不要重试。",
            "",
            str(req.get("system") or ""),
            "",
            str(req.get("prompt") or ""),
        ])
        launch = ["orchestration", "worker-start", "--from", handle,
                  "--agent", selected_agent, "--worktree", worktree,
                  "--task-title", "ani-rss-subtitle", "--spec", spec, "--timeout-ms", "60000"]
        if selected_model:
            launch.extend(["--model", selected_model])
        if selected_effort:
            launch.extend(["--effort", selected_effort])
        started = run_json(launch, 70)
        dispatch = str((started or {}).get("dispatchId") or "")
        if re.fullmatch(r"ctx_[A-Za-z0-9]{6,80}", dispatch) is None:
            sys.exit(1)
        state["dispatch"] = dispatch
        task_id = str((started or {}).get("taskId") or "")
        if re.fullmatch(r"task_[A-Za-z0-9]{6,80}", task_id):
            state["task"] = task_id
        deadline = time.time() + 1200
        state_name = ""
        ready = ""
        last_size = -1
        stable = 0
        extended = False
        while time.time() < deadline:
            show = run_json(["orchestration", "worker-show", "--dispatch", dispatch], 20)
            state_name = worker_state(show)
            terminal = show.get("terminal") if isinstance(show, dict) else None
            if isinstance(terminal, dict):
                agent = str(terminal.get("handle") or "")
                if re.fullmatch(r"term_[A-Za-z0-9-]{8,80}", agent):
                    state["agent"] = agent
            text = read_out(out_path)
            if "</terminology>" in text and useful(text):
                size = len(text)
                if size == last_size:
                    stable += 1
                else:
                    stable = 0
                    last_size = size
                if stable >= 2 and not ready:
                    ready = text
                    if not extended:
                        deadline = max(deadline, time.time() + 90)
                        extended = True
            elif not ready:
                stable = 0
            if state_name in ("succeeded", "completed"):
                if not ready and useful(text):
                    ready = text
                break
            if state_name in ("failed", "stopped"):
                if not ready and useful(text):
                    ready = text
                break
            time.sleep(2)
        if not ready and state_name in ("succeeded", "completed"):
            checked = run_json(["orchestration", "check", "--terminal", handle, "--run", run_id, "--all"], 20)
            messages = (checked or {}).get("messages") or []
            for message in messages:
                if not isinstance(message, dict) or message.get("type") != "worker_done":
                    continue
                body = str(message.get("body") or "")
                if useful(body):
                    ready = body
                    break
        settled = state_name in ("succeeded", "completed", "failed", "stopped")
        close_worker(settled)
        if not ready:
            finish_task("failed")
            sys.exit(1)
        finish_task("completed")
        sys.stdout.write(ready)
    finally:
        close_coord()
        cleanup_root()



class OrcaFailure(Exception):
    pass


def fetch_catalog(agent=None, current_agent="grok"):
    resources = Path(orca).resolve().parent.parent
    modules = resources / "app.asar.unpacked" / "out"
    node = resources.parent / "MacOS" / "Orca"
    if not node.is_file():
        node = Path(shutil.which("node") or "/nonexistent")
    if not (modules / "cli/runtime-client.js").is_file() or not node.is_file():
        raise OrcaFailure("orca_catalog_runtime_unavailable")
    try:
        result = subprocess.run([str(node), "-e", base64.b64decode(RPC_SOURCE_B64).decode(), str(modules)],
            input=json.dumps({"agent": agent, "currentAgent": current_agent}), text=True,
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=80,
            env={**os.environ, "ELECTRON_RUN_AS_NODE": "1"})
        if len(result.stdout.encode()) > 2 * 1024 * 1024:
            raise OrcaFailure("orca_catalog_too_large")
        value = json.loads(result.stdout)
        if result.returncode or not value.get("ok"):
            raise OrcaFailure("orca_catalog_unavailable")
        return value
    except (OSError, ValueError, subprocess.SubprocessError):
        raise OrcaFailure("orca_catalog_unavailable") from None


def validate_selection(agent, model, effort, catalog):
    if not model:
        raise OrcaFailure("effort_requires_model")
    group = next((g for g in catalog.get("providers", []) if g.get("id") == agent), None)
    if not group or not group.get("supportsModelSelection"):
        raise OrcaFailure("agent_model_selection_unavailable")
    chosen = next((m for m in group.get("models", []) if m.get("id") == model), None)
    if not chosen:
        raise OrcaFailure("selected_model_unavailable")
    if effort and effort not in {e.get("value") for e in chosen.get("efforts", [])}:
        raise OrcaFailure("selected_effort_unavailable")


def main():
    global req, orca, worktree
    req = json.load(sys.stdin)
    orca = os.path.expanduser(str(req.get("orca") or "/usr/local/bin/orca"))
    worktree = str(req.get("worktree") or "").strip()
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGHUP, stop)
    try:
        if req.get("action") == "catalogs":
            result = fetch_catalog(current_agent=str(req.get("agent") or "grok"))
            sys.stdout.write(json.dumps(result, ensure_ascii=False))
        else:
            translate()
    except OrcaFailure as error:
        code = str(error)
        if req.get("action") == "catalogs":
            sys.stdout.write(json.dumps({"ok": False, "error": code}))
        else:
            sys.stderr.write("Orca subtitle request failed: " + code + "\n")
        raise SystemExit(1)


if __name__ == "__main__":
    main()
