"""The phone over adb: the app's control endpoint (ControlProvider.kt) plus the few shell
commands the tests need. Parsing is kept in plain functions so it can be tested on the PC."""
import json
import re
import shlex
import subprocess
import time

PKG = "ro.cobrabm.fexdroid"
CONTROL_URI = f"content://{PKG}.control"
ACTIVITY = f"{PKG}/.MainActivity"
GAMES = ("steam", "dota", "steam-arm64")


class DeviceError(RuntimeError):
    pass


def parse_content_call(text):
    """`content call` prints `Result: Bundle[{json={...}}]`; returns the JSON object."""
    m = re.search(r"json=(\{.*\})\}\]\s*$", text.strip(), re.S)
    if not m:
        raise DeviceError("no answer from the app's control endpoint (an app build older than N-055?): "
                          f"{text.strip()[:300] or 'empty output'}")
    return json.loads(m.group(1))


def parse_battery(text):
    """`dumpsys battery` -> dict(level, charging, source, temp_c)."""
    vals = {}
    for line in text.splitlines():
        if ":" in line:
            k, v = line.split(":", 1)
            vals[k.strip()] = v.strip()
    sources = [s for s in ("AC", "USB", "Wireless", "Dock") if vals.get(f"{s} powered") == "true"]
    status = int(vals.get("status", "0") or 0)  # 2 charging, 5 full
    temp = vals.get("temperature")
    return {
        "level": int(vals.get("level", "-1") or -1),
        "charging": bool(sources) or status in (2, 5),
        "source": ",".join(sources) or ("status %d" % status),
        "temp_c": int(temp) / 10 if temp and temp.lstrip("-").isdigit() else None,
    }


def parse_memory(text):
    """/proc/<pid>/status followed by /proc/meminfo: the process's RAM and swap, the system's
    available memory and free swap, in MB."""
    kb = {}
    for line in text.splitlines():
        m = re.match(r"(\w+):\s+(\d+) kB", line)
        if m and m.group(1) not in kb:
            kb[m.group(1)] = int(m.group(2))
    return {"rss_mb": kb.get("VmRSS", 0) // 1024, "swap_mb": kb.get("VmSwap", 0) // 1024,
            "available_mb": kb.get("MemAvailable", 0) // 1024, "swap_free_mb": kb.get("SwapFree", 0) // 1024}


def parse_screen_state(window_dump, power_dump):
    """Awake, unlocked and our activity in front: the only state in which a tap is allowed."""
    awake = re.search(r"mWakefulness=Awake", power_dump) is not None
    locked = re.search(r"(isKeyguardShowing|mKeyguardShowing|mDreamingLockscreen|mShowingLockscreen)=true",
                       window_dump) is not None
    focus = re.search(r"mCurrentFocus=.*?\{[^}]*\s(\S+)\}", window_dump)
    focused = focus.group(1) if focus else ""
    return {"awake": awake, "locked": locked, "focus": focused,
            "ours": focused.startswith(PKG + "/"),
            "ready": awake and not locked and focused.startswith(PKG + "/")}


class Device:
    """One phone, by adb serial. Every call goes through adb; nothing is cached."""

    def __init__(self, serial=None, adb="adb"):
        self.serial = serial
        self.adb = adb

    def _cmd(self, *args):
        base = [self.adb] + (["-s", self.serial] if self.serial else [])
        return base + list(args)

    def run(self, *args, timeout=120, check=True, binary=False):
        p = subprocess.run(self._cmd(*args), capture_output=True, timeout=timeout)
        if check and p.returncode != 0:
            raise DeviceError(f"adb {' '.join(args)[:120]} failed: {p.stderr.decode(errors='replace').strip()}")
        return p.stdout if binary else p.stdout.decode(errors="replace")

    def shell(self, command, timeout=120, check=True):
        return self.run("shell", command, timeout=timeout, check=check)

    def run_as(self, command, timeout=120):
        """A command as the app's uid, in its data directory (debuggable builds)."""
        return self.shell(f"run-as {PKG} sh -c {shlex.quote(command)}", timeout=timeout)

    # --- identity and guards ----------------------------------------------------------
    def getprop(self, name):
        return self.shell(f"getprop {name}").strip()

    def check_target(self):
        """Never drive a device that is not a test phone (an NVIDIA Shield shares the LAN)."""
        maker = self.getprop("ro.product.manufacturer").lower()
        model = self.getprop("ro.product.model")
        if "nvidia" in maker or "shield" in model.lower():
            raise DeviceError(f"refusing to drive {maker} {model}: not a test phone")
        if PKG not in self.shell(f"pm list packages {PKG}"):
            raise DeviceError(f"{PKG} is not installed on {model}")
        return f"{maker} {model}"

    def battery(self):
        return parse_battery(self.shell("dumpsys battery"))

    def screen_state(self):
        return parse_screen_state(self.shell("dumpsys window"), self.shell("dumpsys power"))

    # --- the app ----------------------------------------------------------------------
    def control(self, method, arg=None, timeout=120):
        cmd = f"content call --uri {CONTROL_URI} --method {shlex.quote(method)}"
        if arg is not None:
            cmd += f" --arg {shlex.quote(str(arg))}"
        answer = parse_content_call(self.shell(cmd, timeout=timeout))
        if not answer.get("ok", False):
            raise DeviceError(f"{method}: {answer.get('error', answer)}")
        return answer

    def start_session(self, game):
        if game not in GAMES:
            raise DeviceError(f"unknown game {game}; one of {', '.join(GAMES)}")
        self.shell(f"am start -n {ACTIVITY} --es action {game}")
        # Seen on the Realme: an app left open on Acasă sometimes ignores the intent (onNewIntent)
        # and stays Idle. Start it fresh in that case.
        for _ in range(15):
            time.sleep(1)
            if self.control("status").get("state") != "Idle":
                return
        self.shell(f"am force-stop {PKG}")
        self.shell(f"am start -n {ACTIVITY} --es action {game}")

    def steam_url(self, url):
        """A steam:// link for the running client. The app hands links to the x86 client; the
        arm64 client gets them the way a second start of Valve's client would pass them, as a
        command line on its pipe."""
        if self.control("status").get("game") == "steam-arm64":
            self.run_as(f"echo {shlex.quote('steam ' + url)} > files/home-arm64/.steam/steam.pipe", timeout=20)
        else:
            self.control("steam-url", url)

    def read_root_file(self, path):
        """A kernel setting (/sys, /proc) read as root; the phone must be rooted."""
        if not re.fullmatch(r"/(sys|proc)/[A-Za-z0-9_./-]+", path) or ".." in path:
            raise DeviceError(f"not a /sys or /proc path: {path}")
        return self.shell(f"su -c {shlex.quote('cat ' + path)}").strip()

    def write_root_file(self, path, value):
        if not re.fullmatch(r"/(sys|proc)/[A-Za-z0-9_./-]+", path) or ".." in path:
            raise DeviceError(f"not a /sys or /proc path: {path}")
        self.shell(f"su -c {shlex.quote('echo ' + shlex.quote(value) + ' > ' + path)}")

    def write_app_file(self, rel_path, text):
        """files/<rel_path> in the app's data, through /data/local/tmp (run-as reads it there)."""
        if rel_path.startswith("/") or ".." in rel_path.split("/"):
            raise DeviceError(f"not a path under files/: {rel_path}")
        tmp = "/data/local/tmp/fxctl-upload"
        p = subprocess.run(self._cmd("shell", f"cat > {tmp}"), input=text.encode(), capture_output=True, timeout=60)
        if p.returncode != 0:
            raise DeviceError(f"upload failed: {p.stderr.decode(errors='replace')}")
        self.run_as(f"mkdir -p \"$(dirname files/{shlex.quote(rel_path)})\" && cat {tmp} > files/{shlex.quote(rel_path)}")
        self.shell(f"rm -f {tmp}")

    def remove_app_file(self, rel_path):
        if rel_path.startswith("/") or ".." in rel_path.split("/"):
            raise DeviceError(f"not a path under files/: {rel_path}")
        self.run_as(f"rm -f files/{shlex.quote(rel_path)}")

    def grep_count(self, rel_path, pattern):
        out = self.run_as(f"grep -cF -- {shlex.quote(pattern)} files/{shlex.quote(rel_path)} 2>/dev/null || true")
        digits = out.strip().splitlines()[-1:] or ["0"]
        return int(digits[0]) if digits[0].isdigit() else 0

    def kill_matching(self, pattern):
        """Kills the app's processes whose command line has [pattern] (the game before a new start)."""
        out = self.run_as("ps -A -o PID,ARGS")
        pids = [l.split()[0] for l in out.splitlines()[1:] if pattern in l and "ps -A" not in l]
        if pids:
            self.run_as("kill " + " ".join(pids))
        return pids

    def process_memory(self, pattern):
        """RAM and swap (MB) of the first of the app's processes whose command line has [pattern],
        and what the system still has available; None when no process matches."""
        out = self.run_as("ps -A -o PID,ARGS")
        pids = [l.split()[0] for l in out.splitlines()[1:] if pattern in l and "ps -A" not in l]
        if not pids:
            return None
        status = self.run_as(f"cat /proc/{pids[0]}/status /proc/meminfo")
        return parse_memory(status)

    def screencap_raw(self):
        return self.run("exec-out", "screencap", binary=True, timeout=60)

    def input(self, source, *args):
        self.shell("input " + source + " " + " ".join(str(a) for a in args))

    def cpu_caps(self):
        out = self.shell("for c in 7 4 0; do cat /sys/devices/system/cpu/cpu$c/cpufreq/scaling_max_freq 2>/dev/null; done")
        return "/".join(str(int(x) // 1000) for x in out.split() if x.isdigit())

    @staticmethod
    def now():
        return time.monotonic()
