"""Host tests for fxctl: parsing, scenario checks and a full run against a pretend phone.
Run: python3 -m unittest discover -s tools/fxctl/tests"""
import json
import os
import struct
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import device  # noqa: E402
import scenario  # noqa: E402
import screen  # noqa: E402
import stats  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
SCENARIOS = os.path.join(HERE, "..", "scenarios")


def raw_image(w, h, fill=(0, 0, 0), header=16, dots=()):
    px = bytearray(bytes([*fill, 255]) * (w * h))
    for (x, y), rgb in dots:
        i = (y * w + x) * 4
        px[i:i + 3] = bytes(rgb)
    head = struct.pack("<III", w, h, 1) + (b"\0\0\0\0" if header == 16 else b"")
    return head + bytes(px)


class Parsing(unittest.TestCase):
    def test_content_call(self):
        out = 'Result: Bundle[{json={"ok":true,"state":"Running","game":"steam"}}]\n'
        self.assertEqual(device.parse_content_call(out)["state"], "Running")

    def test_content_call_braces_in_strings(self):
        out = 'Result: Bundle[{json={"ok":true,"lines":["a}]","{b"]}}]'
        self.assertEqual(device.parse_content_call(out)["lines"], ["a}]", "{b"])

    def test_content_call_error(self):
        with self.assertRaises(device.DeviceError):
            device.parse_content_call("Error while accessing provider:ro.cobrabm.fexdroid.control")

    def test_battery(self):
        txt = "Current Battery Service state:\n  AC powered: false\n  USB powered: true\n  Wireless powered: false\n" \
              "  status: 2\n  level: 78\n  temperature: 352\n"
        b = device.parse_battery(txt)
        self.assertEqual((b["charging"], b["source"], b["level"], b["temp_c"]), (True, "USB", 78, 35.2))

    def test_battery_discharging(self):
        b = device.parse_battery("  AC powered: false\n  USB powered: false\n  status: 3\n  level: 50\n")
        self.assertFalse(b["charging"])

    def test_screen_state(self):
        win = "  mCurrentFocus=Window{1a2b u0 ro.cobrabm.fexdroid/ro.cobrabm.fexdroid.MainActivity}\n" \
              "  mDreamingLockscreen=false\n"
        self.assertTrue(device.parse_screen_state(win, "mWakefulness=Awake")["ready"])
        locked = win.replace("mDreamingLockscreen=false", "mDreamingLockscreen=true")
        self.assertFalse(device.parse_screen_state(locked, "mWakefulness=Awake")["ready"])
        other = "  mCurrentFocus=Window{1 u0 NotificationShade}\n"
        self.assertFalse(device.parse_screen_state(other, "mWakefulness=Awake")["ready"])
        self.assertFalse(device.parse_screen_state(win, "mWakefulness=Asleep")["ready"])

    def test_raw_screencap(self):
        for header in (12, 16):
            img = screen.parse_raw(raw_image(4, 3, header=header, dots=[((2, 1), (10, 200, 30))]))
            self.assertEqual(img.pixel(2, 1), (10, 200, 30))
            self.assertEqual(img.brightest_x(1, 0, 3), 2)
        with self.assertRaises(ValueError):
            screen.parse_raw(raw_image(4, 3)[:-5])

    def test_png(self):
        png = screen.parse_raw(raw_image(3, 2)).png()
        self.assertTrue(png.startswith(b"\x89PNG"))

    def test_colors(self):
        self.assertTrue(screen.matches_color((40, 160, 50), {"dominant": "green"}))
        self.assertFalse(screen.matches_color((120, 130, 50), {"dominant": "green"}))
        self.assertTrue(screen.matches_color((100, 100, 100), {"rgb": [110, 95, 100], "tol": 20}))


class Stats(unittest.TestCase):
    def test_summary(self):
        s = stats.summary([24, 26, 25])
        self.assertAlmostEqual(s["mean"], 25)
        self.assertAlmostEqual(s["sd"], 1)

    def test_compare_noise(self):
        self.assertEqual(stats.compare([24, 26, 25], [25, 27, 26])["verdict"], "within noise")

    def test_compare_real_difference(self):
        c = stats.compare([20, 20.5, 19.5], [26, 26.5, 25.5])
        self.assertEqual(c["verdict"], "faster")
        self.assertGreater(c["diff_pct"], 25)

    def test_compare_single_runs(self):
        self.assertEqual(stats.compare([20], [30])["verdict"], "one run each: no verdict")

    def test_schedule(self):
        self.assertEqual(stats.schedule(["A", "B"], 2), [(0, "A"), (0, "B"), (1, "B"), (1, "A")])
        self.assertEqual(stats.schedule(["A", "B"], 2, "interleaved"), [(0, "A"), (0, "B"), (1, "A"), (1, "B")])
        self.assertEqual(stats.schedule(["A", "B"], 2, "blocks"), [(0, "A"), (1, "A"), (0, "B"), (1, "B")])


class Validation(unittest.TestCase):
    def test_shipped_scenarios_load(self):
        for name in os.listdir(SCENARIOS):
            with self.subTest(name):
                scenario.load(os.path.join(SCENARIOS, name))

    def test_tap_needs_expect(self):
        with self.assertRaises(scenario.ScenarioError) as e:
            scenario.validate({"name": "x", "run": [{"do": "tap", "x": 1, "y": 2}]})
        self.assertIn("expect", str(e.exception))

    def test_unknown_things(self):
        bad = {"name": "x", "run": [{"do": "teleport"}, {"do": "wait", "until": {"moon": 1}},
                                    {"do": "sleep", "seconds": 1, "extra": 2}], "colour": 1}
        with self.assertRaises(scenario.ScenarioError) as e:
            scenario.validate(bad)
        msg = str(e.exception)
        for part in ("teleport", "moon", "extra", "colour"):
            self.assertIn(part, msg)

    def test_bad_regex_and_link(self):
        with self.assertRaises(scenario.ScenarioError):
            scenario.validate({"name": "x", "run": [{"do": "wait", "until": {"focus": "("}}]})
        with self.assertRaises(scenario.ScenarioError):
            scenario.validate({"name": "x", "run": [{"do": "steam_url", "url": "http://evil"}]})

    def test_variants(self):
        with self.assertRaises(scenario.ScenarioError):
            scenario.validate({"name": "x", "run": [], "variants": [{"name": "a"}, {"name": "a"}]})
        with self.assertRaises(scenario.ScenarioError):
            scenario.validate({"name": "x", "run": [], "variants": [{"name": "a", "session_env": ["NOEQUALS"]}]})
        with self.assertRaises(scenario.ScenarioError):
            scenario.validate({"name": "x", "run": [], "variants": [{"name": "a", "files": {"../x": ""}}]})

    def test_defaults(self):
        sc = scenario.validate({"name": "x", "run": []})
        self.assertEqual((sc["repeat"], sc["order"], sc["requires_charging"]), (1, "abba", True))


class FakePhone:
    """Enough of a phone for the runner: a session that starts, frames that count up at a
    rate set per variant through session-env.txt, a screen that can be locked."""

    def __init__(self, charging=True, locked=False, fps_by_env=None):
        self.charging, self.locked = charging, locked
        self.fps_by_env = fps_by_env or {}
        self.files = {"session-env.txt": "MY_EXPERIMENT=1\n"}
        self.root = {"/sys/kernel/mm/transparent_hugepage/enabled": "always madvise [never]"}
        self.t_ms = 0
        self.frames = 0
        self.state = "Idle"
        self.inputs = []
        self.handle_x = 2100
        self.log = []
        self.game_started = False

    # time passes only through sleep
    def advance(self, seconds):
        self.t_ms += int(seconds * 1000)
        if self.state == "Running":
            env = self.files.get("session-env.txt", "")
            rate = next((v for k, v in self.fps_by_env.items() if k in env), 25.0)
            self.frames += int(rate * seconds)

    def check_target(self):
        return "Fake Phone"

    def battery(self):
        return {"level": 80, "charging": self.charging, "source": "AC" if self.charging else "status 3", "temp_c": 33.0}

    def screen_state(self):
        return {"awake": True, "locked": self.locked, "focus": "ro.cobrabm.fexdroid/.MainActivity",
                "ours": True, "ready": not self.locked}

    def control(self, method, arg=None, timeout=120):
        self.log.append(method)
        if method == "status":
            return {"ok": True, "state": self.state, "game": "steam" if self.state != "Idle" else None}
        if method == "counters":
            return {"ok": True, "counters": {"elapsedMs": self.t_ms, "frames": self.frames, "changed": self.frames, "direct": 0}}
        if method == "focus":
            title = "Dota 2" if self.game_started else "Steam Big Picture Mode"
            return {"ok": True, "focus": [f'focus 0x1, toplevel 0x2 "{title}"']}
        if method == "procs":
            # Names as on the Realme: the game shows up as "dota".
            procs = [{"pid": 1, "name": "steamwebhelper", "rssKb": 1}]
            if self.game_started:
                procs.append({"pid": 2, "name": "dota", "rssKb": 1})
            return {"ok": True, "procs": procs}
        if method == "stop":
            self.state, self.game_started = "Idle", False
            return {"ok": True}
        if method == "steam-url":
            self.game_started = True
            return {"ok": True}
        if method == "log":
            return {"ok": True, "lines": []}
        raise device.DeviceError(method)

    def start_session(self, game):
        self.state = "Running"
        self.started = game

    def steam_url(self, url):
        self.control("steam-url", url)

    def run_as(self, command, timeout=120):
        if command.startswith("cat files/"):
            name = command.split()[1][len("files/"):].strip("'")
            if name in self.files:
                return self.files[name] + "__rc=0\n"
            return "__rc=1\n"
        return ""

    def write_app_file(self, rel, text):
        self.files[rel] = text

    def remove_app_file(self, rel):
        self.files.pop(rel, None)

    def grep_count(self, rel, pattern):
        return 1

    def kill_matching(self, pattern):
        return []

    def read_root_file(self, path):
        return self.root.get(path, "")

    def write_root_file(self, path, value):
        self.root[path] = value

    def process_memory(self, pattern):
        return {"rss_mb": 2400, "swap_mb": 3500, "available_mb": 1300, "swap_free_mb": 1100}

    def screencap_raw(self):
        return raw_image(2400, 300, dots=[((self.handle_x, 239), (255, 255, 255))])

    def input(self, source, *args):
        self.inputs.append((source,) + args)
        if args and args[0] == "swipe":
            self.handle_x = args[3]

    def cpu_caps(self):
        return "1555/1555/1804"


def fast_runner(phone, sc, d, force=False):
    out = []
    r = scenario.Runner(phone, sc, d, force=force, out=out.append, sleep=phone.advance, clock=lambda: phone.t_ms / 1000)
    return r, out


class Running(unittest.TestCase):
    def test_dota_scenario_ab(self):
        phone = FakePhone(fps_by_env={"MESA_VK_WSI_PRESENT_MODE=fifo": 20.0})
        sc = scenario.load(os.path.join(SCENARIOS, "dota-replay-ab.json"))
        with tempfile.TemporaryDirectory() as d:
            r, out = fast_runner(phone, sc, d)
            summary = r.run()
            self.assertEqual(len(r.samples), 6, "\n".join(out))
            self.assertIn("relaxed", summary)
            self.assertIn("fifo vs relaxed", summary)
            self.assertIn("slower", summary)
            self.assertTrue(os.path.exists(os.path.join(d, "results.jsonl")))
            self.assertTrue(any(n.endswith("start.png") for n in os.listdir(d)))
        # the slider was dragged with the mouse, and only after the check
        self.assertTrue(any(i[0] == "mouse" and i[1] == "swipe" for i in phone.inputs))
        # the user's own session-env.txt is back, the launch options written are removed again
        self.assertEqual(phone.files["session-env.txt"], "MY_EXPERIMENT=1\n")
        self.assertNotIn("launch-options-570.txt", phone.files)

    def test_refuses_without_charger(self):
        phone = FakePhone(charging=False)
        sc = scenario.load(os.path.join(SCENARIOS, "steam-smoke.json"))
        with tempfile.TemporaryDirectory() as d:
            r, _ = fast_runner(phone, sc, d)
            with self.assertRaises(scenario.StepFailed):
                r.run()
        self.assertEqual(phone.state, "Idle")

    def test_no_input_on_locked_screen(self):
        phone = FakePhone(locked=True)
        sc = scenario.validate({"name": "x", "run": [
            {"do": "start_session", "game": "steam"},
            {"do": "tap", "x": 5, "y": 5, "expect": {"process": "steamwebhelper"}}]})
        with tempfile.TemporaryDirectory() as d:
            r, _ = fast_runner(phone, sc, d)
            r.run()
        self.assertEqual(phone.inputs, [])
        self.assertIn("locked", r.failures[0]["error"])

    def test_no_input_when_expectation_fails(self):
        phone = FakePhone()
        sc = scenario.validate({"name": "x", "run": [
            {"do": "tap", "x": 5, "y": 5, "expect": {"focus": "Some Dialog"}, "expect_timeout": 4}]})
        with tempfile.TemporaryDirectory() as d:
            r, _ = fast_runner(phone, sc, d)
            r.run()
        self.assertEqual(phone.inputs, [])
        self.assertIn("still not true", r.failures[0]["error"])

    def test_variant_files(self):
        phone = FakePhone()
        phone.files["tuner.txt"] = "pin\n"
        sc = scenario.validate({"name": "x", "run": [{"do": "apply_variant"}], "variants": [
            {"name": "off", "files": {"tuner.txt": "off\n", "new.txt": "1\n"}}, {"name": "default"}]})
        with tempfile.TemporaryDirectory() as d:
            r, _ = fast_runner(phone, sc, d)
            r.variant = sc["variants"][0]
            r.step({"do": "apply_variant"}, 0)
            self.assertEqual((phone.files["tuner.txt"], phone.files["new.txt"]), ("off\n", "1\n"))
            r.variant = sc["variants"][1]
            r.step({"do": "apply_variant"}, 0)
            self.assertEqual(phone.files["tuner.txt"], "pin\n")
            self.assertNotIn("new.txt", phone.files)

    def test_root_files(self):
        phone = FakePhone()
        thp = "/sys/kernel/mm/transparent_hugepage/enabled"
        sc = scenario.validate({"name": "x", "run": [{"do": "apply_variant"}], "variants": [
            {"name": "thp", "root_files": {thp: "madvise"}}, {"name": "base"}]})
        seen = []
        with tempfile.TemporaryDirectory() as d:
            r, _ = fast_runner(phone, sc, d)
            real = phone.write_root_file
            phone.write_root_file = lambda p, v: (seen.append(v), real(p, v))
            r.run()
        self.assertEqual(seen[:2], ["madvise", "never"])   # thp round, base round
        self.assertEqual(phone.root[thp], "never")           # put back at the end
        with self.assertRaises(scenario.ScenarioError):
            scenario.validate({"name": "x", "run": [], "variants": [{"name": "a", "root_files": {"/data/x": "1"}}]})

    def test_variant_game(self):
        phone = FakePhone()
        sc = scenario.validate({"name": "x", "run": [{"do": "start_session", "game": "steam"}],
                                "variants": [{"name": "arm", "game": "steam-arm64"}]})
        with tempfile.TemporaryDirectory() as d:
            r, _ = fast_runner(phone, sc, d)
            r.run()
        self.assertEqual(phone.started, "steam-arm64")
        with self.assertRaises(scenario.ScenarioError):
            scenario.validate({"name": "x", "run": [], "variants": [{"name": "a", "game": "wine"}]})

    def test_steam_url_resent(self):
        phone = FakePhone()
        phone.state = "Running"
        sent = []
        def steam_url(url):
            sent.append(url)
            if len(sent) == 2:  # the first one was dropped by a client still starting
                phone.game_started = True
        phone.steam_url = steam_url
        sc = scenario.validate({"name": "x", "run": [{"do": "steam_url", "url": "steam://rungameid/570",
                                "until": {"process": "^dota$"}, "timeout": 300, "resend_every": 60}]})
        with tempfile.TemporaryDirectory() as d:
            r, _ = fast_runner(phone, sc, d)
            r.run()
        self.assertEqual(len(sent), 2)
        self.assertEqual(r.failures, [])

    def test_memory_step(self):
        phone = FakePhone()
        sc = scenario.validate({"name": "x", "run": [{"do": "memory", "match": "dota2"}]})
        with tempfile.TemporaryDirectory() as d:
            r, _ = fast_runner(phone, sc, d)
            r.run()
            with open(os.path.join(r.dir, "results.jsonl")) as f:
                rec = json.loads(f.readline())
        self.assertEqual(rec["memory"]["swap_mb"], 3500)

    def test_parse_memory(self):
        text = "Name:\tFEX\nVmRSS:\t 2048000 kB\nVmSwap:\t 1024 kB\nMemTotal: 11519924 kB\nMemAvailable: 1317280 kB\nSwapFree: 1089156 kB\n"
        self.assertEqual(device.parse_memory(text), {"rss_mb": 2000, "swap_mb": 1, "available_mb": 1286, "swap_free_mb": 1063})

    def test_counter_reset_window_skipped(self):
        phone = FakePhone()
        phone.state = "Running"
        r, _ = fast_runner(phone, scenario.validate({"name": "x", "run": []}), None)
        r.variant = {"name": "v"}
        real = phone.control

        calls = {"n": 0}
        def control(method, arg=None, timeout=120):
            ans = real(method, arg, timeout)
            if method == "counters":
                calls["n"] += 1
                if calls["n"] == 3:  # the bridge restarted: counters back at 0
                    phone.frames = 0
                    ans["counters"].update(frames=0, changed=0)
            return ans
        phone.control = control
        s = r.measure(40)
        self.assertEqual(len(s["windows"]), 3)


class Cli(unittest.TestCase):
    def test_validate_command(self):
        import fxctl
        self.assertEqual(fxctl.main(["validate", os.path.join(SCENARIOS, "steam-smoke.json")]), 0)


if __name__ == "__main__":
    unittest.main()
