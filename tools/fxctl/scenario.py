"""Scenarios: JSON files of steps (start a session, wait for a state, launch a game, measure
frames) run several times per variant, for A/B comparisons that can be repeated.

Rules that keep it safe on a real phone:
- every tap, swipe or key needs an "expect" condition, checked on a fresh screenshot or state
  just before the input; without it the scenario does not load. The screen must also be awake,
  unlocked and showing our app. Nothing is ever pressed blind.
- the phone must be charging (checked before the run and before every round) unless --force.
- files/session-env.txt and the launch-option files the run writes are put back afterwards.
"""
import json
import os
import re
import shlex
import time

from device import DeviceError
from screen import matches_color, parse_raw
import stats

UI_STEPS = {"tap", "swipe", "key", "drag_handle"}
STEPS = {
    # do: (required keys, optional keys)
    "stop_session": ((), ("settle",)),
    "start_session": (("game",), ()),
    "apply_variant": ((), ()),
    "steam_url": (("url",), ("until", "timeout", "resend_every")),
    "wait": (("until",), ("timeout", "interval")),
    "assert": (("that",), ()),
    "sleep": (("seconds",), ()),
    "remove_file": (("path",), ()),
    "write_file": (("path", "text"), ()),
    "kill_process": (("match",), ()),
    "memory": (("match",), ("label",)),
    "screenshot": (("save",), ()),
    "measure_fps": (("seconds",), ("label", "window", "allow_hidden")),
    "tap": (("x", "y", "expect"), ("source", "expect_timeout")),
    "swipe": (("from", "to", "expect"), ("ms", "source", "expect_timeout")),
    "key": (("key", "expect"), ("expect_timeout",)),
    "drag_handle": (("row_y", "x_range", "to_x", "accept_min_x", "expect"),
                    ("ms", "source", "retries", "settle", "expect_timeout")),
}
CONDITIONS = {"state", "game", "focus", "process", "log", "guest_grep", "pixel", "screen_ready",
              "fps_above", "all", "any", "not"}
ORDERS = ("abba", "interleaved", "blocks")


class ScenarioError(ValueError):
    pass


class StepFailed(RuntimeError):
    pass


# --- loading ---------------------------------------------------------------------------
def _check_condition(cond, where, errors):
    if not isinstance(cond, dict) or not cond:
        errors.append(f"{where}: a condition is a non-empty object")
        return
    for k, v in cond.items():
        if k not in CONDITIONS:
            errors.append(f"{where}: unknown condition {k!r} (known: {', '.join(sorted(CONDITIONS))})")
        elif k in ("all", "any"):
            if not isinstance(v, list) or not v:
                errors.append(f"{where}.{k}: a non-empty list of conditions")
            else:
                for i, c in enumerate(v):
                    _check_condition(c, f"{where}.{k}[{i}]", errors)
        elif k == "not":
            _check_condition(v, f"{where}.not", errors)
        elif k in ("focus", "process", "log"):
            try:
                re.compile(v)
            except (re.error, TypeError) as e:
                errors.append(f"{where}.{k}: bad regex: {e}")
        elif k == "guest_grep" and not (isinstance(v, dict) and "file" in v and "pattern" in v):
            errors.append(f"{where}.guest_grep: needs file and pattern")
        elif k == "pixel" and not (isinstance(v, dict) and "x" in v and "y" in v and ("rgb" in v or "dominant" in v)):
            errors.append(f"{where}.pixel: needs x, y and rgb or dominant")


def _check_steps(steps, where, errors):
    if not isinstance(steps, list):
        errors.append(f"{where}: a list of steps")
        return
    for i, st in enumerate(steps):
        w = f"{where}[{i}]"
        if not isinstance(st, dict) or "do" not in st:
            errors.append(f"{w}: each step is an object with \"do\"")
            continue
        kind = st["do"]
        if kind not in STEPS:
            errors.append(f"{w}: unknown step {kind!r}")
            continue
        req, opt = STEPS[kind]
        for k in req:
            if k not in st:
                why = " (no input without a check of the screen first)" if k == "expect" else ""
                errors.append(f"{w} {kind}: missing {k!r}{why}")
        for k in st:
            if k not in req and k not in opt and k not in ("do", "note"):
                errors.append(f"{w} {kind}: unknown key {k!r}")
        for k in ("until", "that", "expect"):
            if k in st:
                _check_condition(st[k], f"{w}.{k}", errors)
        if kind == "start_session" and st.get("game") not in ("steam", "dota", "steam-arm64"):
            errors.append(f"{w}: game is steam, dota or steam-arm64")
        if kind == "steam_url" and not re.fullmatch(r"steam://[a-z]+(/[A-Za-z0-9_.-]+)*/?", str(st.get("url", ""))):
            errors.append(f"{w}: not a steam:// link the app accepts")


def validate(sc):
    """Raises ScenarioError with every problem found; returns the scenario with defaults."""
    errors = []
    if not isinstance(sc, dict):
        raise ScenarioError("a scenario is a JSON object")
    for k in sc:
        if k not in ("name", "description", "repeat", "order", "noise_pct", "requires_charging", "min_battery",
                     "launch_options", "session_env", "variants", "setup", "run", "teardown", "note"):
            errors.append(f"unknown key {k!r}")
    if not isinstance(sc.get("name"), str) or not re.fullmatch(r"[A-Za-z0-9_.-]+", sc.get("name", "")):
        errors.append("name: letters, digits, . _ -")
    if "run" not in sc:
        errors.append("run: the steps of one round are required")
    if sc.get("order", "abba") not in ORDERS:
        errors.append(f"order: one of {', '.join(ORDERS)}")
    if not isinstance(sc.get("repeat", 1), int) or sc.get("repeat", 1) < 1:
        errors.append("repeat: a whole number >= 1")
    variants = sc.get("variants", [{"name": "default"}])
    names = set()
    if not isinstance(variants, list) or not variants:
        errors.append("variants: a non-empty list")
        variants = []
    for i, v in enumerate(variants):
        if not isinstance(v, dict) or not isinstance(v.get("name"), str):
            errors.append(f"variants[{i}]: needs a name")
            continue
        if v["name"] in names:
            errors.append(f"variants[{i}]: name {v['name']!r} used twice")
        names.add(v["name"])
        for k in v:
            if k not in ("name", "session_env", "launch_options", "files", "root_files", "copy_files", "game", "note"):
                errors.append(f"variants[{i}]: unknown key {k!r}")
        if not all(isinstance(x, str) and "=" in x for x in v.get("session_env", [])):
            errors.append(f"variants[{i}].session_env: KEY=VALUE strings")
        cf = v.get("copy_files", {})
        if not isinstance(cf, dict) or any(not isinstance(t, str) or f.startswith("/") or t.startswith("/") or ".." in f + t
                                           for f, t in cf.items()):
            errors.append(f"variants[{i}].copy_files: {{\"path under files/\": \"path under files/ to copy from\"}}")
        rf = v.get("root_files", {})
        if not isinstance(rf, dict) or not all(isinstance(t, str) and re.fullmatch(r"/(sys|proc)/[A-Za-z0-9_./-]+", f)
                                                for f, t in rf.items()):
            errors.append(f"variants[{i}].root_files: {{\"/sys/... or /proc/...\": \"value\"}}")
        if v.get("game", "steam") not in ("steam", "dota", "steam-arm64"):
            errors.append(f"variants[{i}].game: steam, dota or steam-arm64 (the session start_session starts)")
        files = v.get("files", {})
        if not isinstance(files, dict) or not all(isinstance(t, str) for t in files.values()):
            errors.append(f"variants[{i}].files: {{\"path under files/\": \"text\"}}")
        elif any(f.startswith("/") or ".." in f.split("/") for f in files):
            errors.append(f"variants[{i}].files: paths under files/")
    for part in ("setup", "run", "teardown"):
        if part in sc:
            _check_steps(sc[part], part, errors)
    if errors:
        raise ScenarioError("\n".join(errors))
    out = dict(sc)
    out.setdefault("repeat", 1)
    out.setdefault("order", "abba")
    out.setdefault("noise_pct", 10.0)
    out.setdefault("requires_charging", True)
    out.setdefault("min_battery", 30)
    out["variants"] = variants
    return out


def load(path):
    with open(path) as f:
        try:
            sc = json.load(f)
        except json.JSONDecodeError as e:
            raise ScenarioError(f"{path}: {e}")
    return validate(sc)


# --- running ---------------------------------------------------------------------------
class Runner:
    def __init__(self, dev, sc, results_dir, force=False, out=print, sleep=time.sleep, clock=time.monotonic):
        self.dev, self.sc, self.dir, self.force = dev, sc, results_dir, force
        self.out, self.sleep, self.clock = out, sleep, clock
        self.variant = None
        self.samples = []      # dicts: variant, round, label, fps, ...
        self.failures = []
        self._restore = {}     # app files we overwrote -> previous text (None: did not exist)
        self._copied = set()     # app files replaced by copy_files (originals in <name>.fxctl-orig)
        self._root_restore = {}  # kernel settings (root) we changed -> previous value

    # Guards -----------------------------------------------------------------------------
    def guard_power(self):
        b = self.dev.battery()
        if self.sc["requires_charging"] and not b["charging"]:
            if not self.force:
                raise StepFailed(f"the phone is not charging (battery {b['level']}%): plug it in, or --force")
            self.out(f"warning: not charging ({b['level']}%), going on because of --force")
        if b["level"] >= 0 and b["level"] < self.sc["min_battery"] and not self.force:
            raise StepFailed(f"battery at {b['level']}%, below {self.sc['min_battery']}%: let it charge first")
        if b["charging"] and b["source"] == "USB":
            self.out("note: charging from USB only; a PC port may not keep up under a game (NOTES)")
        return b

    def guard_screen(self):
        s = self.dev.screen_state()
        if not s["ready"]:
            why = "asleep" if not s["awake"] else "locked" if s["locked"] else f"showing {s['focus'] or '?'}"
            raise StepFailed(f"the screen is {why}; inputs are only sent to fexdroid in front, unlocked")
        return s

    # Conditions -------------------------------------------------------------------------
    def check(self, cond, shot=None):
        """(true?, what was seen). One screenshot is shared by all pixel checks of a condition."""
        results = []
        for k, v in cond.items():
            if k == "all":
                parts = [self.check(c, shot) for c in v]
                results.append((all(p[0] for p in parts), "all(" + "; ".join(p[1] for p in parts) + ")"))
            elif k == "any":
                parts = [self.check(c, shot) for c in v]
                results.append((any(p[0] for p in parts), "any(" + "; ".join(p[1] for p in parts) + ")"))
            elif k == "not":
                ok, seen = self.check(v, shot)
                results.append((not ok, f"not({seen})"))
            elif k == "state":
                st = self.dev.control("status")
                results.append((st.get("state") == v, f"state {st.get('state')}"))
            elif k == "game":
                st = self.dev.control("status")
                results.append((st.get("game") == v, f"game {st.get('game')}"))
            elif k == "focus":
                f = " ".join(self.dev.control("focus")["focus"])
                results.append((re.search(v, f) is not None, f"focus {f[:80]!r}"))
            elif k == "process":
                names = [p["name"] for p in self.dev.control("procs")["procs"]]
                hit = [n for n in names if re.search(v, n)]
                results.append((bool(hit), f"process {hit[0] if hit else 'none of ' + str(len(names))}"))
            elif k == "log":
                lines = self.dev.control("log", 300)["lines"]
                hit = [l for l in lines if re.search(v, l)]
                results.append((bool(hit), f"log {hit[-1][:80]!r}" if hit else "log: no match"))
            elif k == "guest_grep":
                n = self.dev.grep_count(v["file"], v["pattern"])
                results.append((n >= v.get("min", 1), f"{os.path.basename(v['file'])}: {n} match(es)"))
            elif k == "pixel":
                if shot is None:
                    shot = parse_raw(self.dev.screencap_raw())
                rgb = shot.pixel(v["x"], v["y"])
                results.append((matches_color(rgb, v), f"pixel {v['x']},{v['y']} = {rgb}"))
            elif k == "screen_ready":
                s = self.dev.screen_state()
                results.append((s["ready"] == bool(v), f"screen ready {s['ready']}"))
            elif k == "fps_above":
                fps = self.fps_window(10)
                results.append((fps > v, f"fps {fps:.1f}"))
        return all(r[0] for r in results), "; ".join(r[1] for r in results)

    def wait_for(self, cond, timeout, interval):
        end = self.clock() + timeout
        while True:
            ok, seen = self.check(cond)
            if ok:
                return seen
            if self.clock() >= end:
                raise StepFailed(f"still not true after {timeout} s: {seen}")
            self.sleep(interval)

    # Frames -----------------------------------------------------------------------------
    @staticmethod
    def frame_sum(c):
        return c["changed"] + c["direct"]

    def fps_window(self, seconds):
        a = self.dev.control("counters")["counters"]
        self.sleep(seconds)
        b = self.dev.control("counters")["counters"]
        dt = (b["elapsedMs"] - a["elapsedMs"]) / 1000
        df = self.frame_sum(b) - self.frame_sum(a)
        return df / dt if dt > 0 and df >= 0 else 0.0

    def measure(self, seconds, window=10, label="fps", allow_hidden=False):
        if not allow_hidden:
            self.guard_screen()  # the display bridge only copies while our picture is on screen
        t0 = self.dev.battery()["temp_c"]
        windows, caps = [], []
        prev = self.dev.control("counters")["counters"]
        for _ in range(max(1, int(seconds // window))):
            self.sleep(window)
            cur = self.dev.control("counters")["counters"]
            dt = (cur["elapsedMs"] - prev["elapsedMs"]) / 1000
            df = self.frame_sum(cur) - self.frame_sum(prev)
            if dt > 0 and df >= 0:  # a counter that went back: the bridge restarted, skip the window
                windows.append(df / dt)
            caps.append(self.dev.cpu_caps())
            prev = cur
        t1 = self.dev.battery()["temp_c"]
        if not windows:
            raise StepFailed("no frame counts during the measurement")
        half = windows[len(windows) // 2:]
        sample = {"label": label, "fps": sum(windows) / len(windows), "fps_second_half": sum(half) / len(half),
                  "windows": [round(w, 1) for w in windows], "cpu_caps_mhz": caps, "battery_c": [t0, t1]}
        self.out(f"   [{self.variant['name']}] {label}: {sample['fps']:.1f} fps "
                 f"(second half {sample['fps_second_half']:.1f}), windows {sample['windows']}, battery {t0} -> {t1} C")
        return sample

    # Files the run changes --------------------------------------------------------------
    def remember(self, rel):
        if rel not in self._restore:
            out = self.dev.run_as(f"cat files/{shlex.quote(rel)} 2>/dev/null; echo \"__rc=$?\"")
            body, _, rc = out.rpartition("__rc=")
            # echo starts after the file's last byte, so body is the file exactly.
            self._restore[rel] = body if rc.strip() == "0" else None

    def write(self, rel, text):
        self.remember(rel)
        self.dev.write_app_file(rel, text)

    def restore_files(self):
        for rel in sorted(self._copied):
            try:
                self.dev.run_as(f"mv files/{shlex.quote(rel)}.fxctl-orig files/{shlex.quote(rel)}")
            except DeviceError as e:
                self.out(f"warning: could not put back files/{rel}: {e}")
        self._copied.clear()
        for path, value in self._root_restore.items():
            try:
                self.dev.write_root_file(path, value)
            except DeviceError as e:
                self.out(f"warning: could not put back {path}: {e}")
        self._root_restore.clear()
        for rel, text in self._restore.items():
            try:
                if text is None:
                    self.dev.remove_app_file(rel)
                else:
                    self.dev.write_app_file(rel, text)
            except DeviceError as e:
                self.out(f"warning: could not put back files/{rel}: {e}")
        self._restore.clear()

    # Steps ------------------------------------------------------------------------------
    def expect(self, st):
        self.guard_screen()
        seen = self.wait_for(st["expect"], st.get("expect_timeout", 10), 2)
        self.out(f"   checked before input: {seen}")

    def step(self, st, rnd):
        kind = st["do"]
        if kind in UI_STEPS:
            self.expect(st)
        if kind == "stop_session":
            self.dev.control("stop")
            self.sleep(st.get("settle", 5))
        elif kind == "start_session":
            self.dev.start_session(self.variant.get("game", st["game"]))
        elif kind == "apply_variant":
            env = list(self.sc.get("session_env", [])) + list(self.variant.get("session_env", []))
            self.write("session-env.txt", "".join(l + "\n" for l in env))
            opts = dict(self.sc.get("launch_options", {}))
            opts.update(self.variant.get("launch_options", {}))
            for appid, line in opts.items():
                self.write(f"launch-options-{appid}.txt", line + "\n")
            # Files of the app replaced by others of the app (a FEX build against another): the
            # original is kept aside once and put back for the variants that do not name it.
            mine_copy = self.variant.get("copy_files", {})
            for rel in sorted({f for v in self.sc["variants"] for f in v.get("copy_files", {})}):
                if rel not in self._copied:
                    self.dev.run_as(f"cp -p files/{shlex.quote(rel)} files/{shlex.quote(rel)}.fxctl-orig")
                    self._copied.add(rel)
                src = mine_copy.get(rel, rel + ".fxctl-orig")
                self.dev.run_as(f"cp files/{shlex.quote(src)} files/{shlex.quote(rel)}.fxctl-new && "
                                f"mv files/{shlex.quote(rel)}.fxctl-new files/{shlex.quote(rel)}")
            # Kernel settings (root): the variant's value, or the one found before the run.
            mine_root = self.variant.get("root_files", {})
            for path in sorted({f for v in self.sc["variants"] for f in v.get("root_files", {})}):
                if path not in self._root_restore:
                    current = self.dev.read_root_file(path)
                    # /sys/kernel/mm/transparent_hugepage/enabled shows "always madvise [never]"
                    chosen = re.search(r"\[(\w+)\]", current)
                    self._root_restore[path] = chosen.group(1) if chosen else current
                self.dev.write_root_file(path, mine_root.get(path, self._root_restore[path]))
            # A file one variant writes is put back as it was for the variants that do not.
            mine = self.variant.get("files", {})
            for rel in sorted({f for v in self.sc["variants"] for f in v.get("files", {})}):
                if rel in mine:
                    self.write(rel, mine[rel])
                else:
                    self.remember(rel)
                    if self._restore[rel] is None:
                        self.dev.remove_app_file(rel)
                    else:
                        self.dev.write_app_file(rel, self._restore[rel])
        elif kind == "steam_url":
            self.steam_url(st)
        elif kind == "wait":
            seen = self.wait_for(st["until"], st.get("timeout", 60), st.get("interval", 5))
            self.out(f"   ok: {seen}")
        elif kind == "assert":
            ok, seen = self.check(st["that"])
            if not ok:
                raise StepFailed(f"assert failed: {seen}")
        elif kind == "sleep":
            self.sleep(st["seconds"])
        elif kind == "remove_file":  # a log the run waits for afresh: not put back
            self.dev.remove_app_file(st["path"])
        elif kind == "write_file":
            self.write(st["path"], st["text"])
        elif kind == "kill_process":
            pids = self.dev.kill_matching(st["match"])
            if pids:
                self.out(f"   stopped {st['match']}: {' '.join(pids)}")
        elif kind == "memory":
            mem = self.dev.process_memory(st["match"])
            if mem is None:
                raise StepFailed(f"no process matches {st['match']}")
            self.out(f"   [{self.variant['name']}] memory: {mem['rss_mb']} MB in RAM + {mem['swap_mb']} MB in swap; "
                     f"available {mem['available_mb']} MB, swap free {mem['swap_free_mb']} MB")
            self.save(dict(label=st.get("label", "memory"), memory=mem, variant=self.variant["name"], round=rnd))
        elif kind == "screenshot":
            shot = parse_raw(self.dev.screencap_raw())
            name = f"{rnd:02d}-{self.variant['name']}-{st['save']}.png"
            with open(os.path.join(self.dir, name), "wb") as f:
                f.write(shot.png())
        elif kind == "measure_fps":
            s = self.measure(st["seconds"], st.get("window", 10), st.get("label", "fps"), st.get("allow_hidden", False))
            s.update(variant=self.variant["name"], round=rnd)
            self.samples.append(s)
            self.save(s)
        elif kind == "tap":
            self.dev.input(st.get("source", "touchscreen"), "tap", st["x"], st["y"])
        elif kind == "swipe":
            self.dev.input(st.get("source", "touchscreen"), "swipe", *st["from"], *st["to"], st.get("ms", 300))
        elif kind == "key":
            self.dev.input("keyevent", st["key"])
        elif kind == "drag_handle":
            self.drag_handle(st)

    def steam_url(self, st):
        """The link, and with "until" sent again every resend_every seconds until the condition
        holds: a client that is still starting can drop it (the arm64 one, from its pipe)."""
        self.dev.steam_url(st["url"])
        if "until" not in st:
            return
        deadline = self.clock() + st.get("timeout", 180)
        every = st.get("resend_every", 60)
        while True:
            try:
                seen = self.wait_for(st["until"], min(every, max(1, deadline - self.clock())), 10)
                self.out(f"   ok: {seen}")
                return
            except StepFailed:
                if self.clock() >= deadline:
                    raise
                self.out("   sent again")
                self.dev.steam_url(st["url"])

    def drag_handle(self, st):
        """A slider (Dota's replay timeline): find the handle as the brightest spot on its row,
        drag it, look again; repeat until it is where it should be (the seek does not always take)."""
        x0, x1 = st["x_range"]
        y = st["row_y"]
        for attempt in range(st.get("retries", 3)):
            x = parse_raw(self.dev.screencap_raw()).brightest_x(y, x0, x1)
            if x >= st["accept_min_x"]:
                self.out(f"   handle at {x}: in place")
                return
            if attempt:
                self.guard_screen()
            self.dev.input(st.get("source", "touchscreen"), "swipe", x, y, st["to_x"], y, st.get("ms", 800))
            self.sleep(st.get("settle", 8))
        x = parse_raw(self.dev.screencap_raw()).brightest_x(y, x0, x1)
        if x < st["accept_min_x"]:
            raise StepFailed(f"the handle did not move far enough (at {x}, wanted >= {st['accept_min_x']})")
        self.out(f"   handle at {x}: in place")

    def save(self, record):
        with open(os.path.join(self.dir, "results.jsonl"), "a") as f:
            f.write(json.dumps(record) + "\n")

    def run_steps(self, steps, rnd):
        for i, st in enumerate(steps):
            self.out(f"  - {st['do']}" + (f" ({st['note']})" if "note" in st else ""))
            self.step(st, rnd)

    def run(self):
        os.makedirs(self.dir, exist_ok=True)
        who = self.dev.check_target()
        self.out(f"{self.sc['name']} on {who}; results in {self.dir}")
        self.guard_power()
        order = stats.schedule([v["name"] for v in self.sc["variants"]], self.sc["repeat"], self.sc["order"])
        by_name = {v["name"]: v for v in self.sc["variants"]}
        self.variant = self.sc["variants"][0]
        try:
            if "setup" in self.sc:
                self.out("setup")
                self.run_steps(self.sc["setup"], -1)
            for n, (rnd, name) in enumerate(order, 1):
                self.variant = by_name[name]
                self.out(f"round {rnd + 1}/{self.sc['repeat']}, variant {name} ({n}/{len(order)})")
                try:
                    self.guard_power()
                    self.run_steps(self.sc["run"], rnd)
                except (StepFailed, DeviceError) as e:
                    self.out(f"  FAILED: {e}")
                    self.failures.append({"variant": name, "round": rnd, "error": str(e)})
                    self.save({"variant": name, "round": rnd, "failed": str(e)})
                    if "not charging" in str(e) or "battery at" in str(e):
                        break
        finally:
            try:
                if "teardown" in self.sc:
                    self.out("teardown")
                    self.run_steps(self.sc["teardown"], -1)
            finally:
                self.restore_files()
        return self.report()

    def report(self):
        lines = []
        labels = sorted({s["label"] for s in self.samples})
        names = [v["name"] for v in self.sc["variants"]]
        for label in labels:
            lines.append(f"{label}:")
            per = {n: [s["fps"] for s in self.samples if s["label"] == label and s["variant"] == n] for n in names}
            for n in names:
                s = stats.summary(per[n])
                if s["n"]:
                    lines.append(f"  {n:<16} {s['mean']:6.1f} fps  sd {s['sd']:4.1f}  "
                                 f"min {s['min']:.1f} max {s['max']:.1f}  runs {s['n']}")
                else:
                    lines.append(f"  {n:<16} no runs")
            for n in names[1:]:
                c = stats.compare(per[names[0]], per[n], self.sc["noise_pct"])
                if c["diff_pct"] is not None:
                    t = "" if c["t"] is None else f", t {c['t']:.1f}"
                    lines.append(f"  {n} vs {names[0]}: {c['diff_pct']:+.1f}%{t}: {c['verdict']}")
        if self.failures:
            lines.append(f"{len(self.failures)} round(s) failed: " +
                         "; ".join(f"{f['variant']}#{f['round'] + 1}: {f['error'][:80]}" for f in self.failures))
        return "\n".join(lines) if lines else "no measurements"
