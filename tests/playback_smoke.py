"""Verify embedded playback on an unlocked, connected phone with internet."""
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB", "adb")
REMOTE = "/sdcard/radio-playback-smoke.xml"


def adb(*args):
    return subprocess.check_output([ADB, *args], text=True)


def ui():
    adb("shell", "uiautomator", "dump", REMOTE)
    return ET.fromstring(adb("shell", "cat", REMOTE))


def wait(predicate, message):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(1)
    raise AssertionError(message)


def media_state(state):
    result = adb("shell", "dumpsys", "media_session")
    match = re.search(r"Radio Explorer com\.radioexplorer/Radio Explorer/.*?(?=\n    \S|\Z)", result, re.S)
    return match is not None and "state=" + state + "(" in match.group()


def click_text(label, attribute="text"):
    root = ui()
    node = next(n for n in root.iter("node") if n.get(attribute) == label)
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


try:
    adb("shell", "am", "force-stop", "com.radioexplorer")
    adb("shell", "am", "start", "-n", "com.radioexplorer/.MainActivity")
    wait(lambda: any(n.get("text") == "Cadena 100" for n in ui().iter("node")),
         "Cadena 100 not found; select Spain and check network")
    click_text("Cadena 100")
    wait(lambda: media_state("PLAYING"), "Embedded stream did not start")
    assert "com.radioexplorer/.MainActivity" in adb("shell", "dumpsys", "activity", "activities"), "App was not visible"
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(3)
    assert media_state("PLAYING"), "Playback stopped in background"
    adb("shell", "cmd", "media_session", "dispatch", "pause")
    wait(lambda: media_state("PAUSED"), "Media pause failed")
    adb("shell", "cmd", "media_session", "dispatch", "play")
    wait(lambda: media_state("PLAYING"), "Media resume failed")
    adb("shell", "am", "start", "-n", "com.radioexplorer/.MainActivity")
    wait(lambda: any(n.get("content-desc") == "Pausar" for n in ui().iter("node")), "Pause control missing")
    click_text("Pausar", "content-desc")
    wait(lambda: media_state("PAUSED"), "Player pause icon failed")
    wait(lambda: any(n.get("content-desc") == "Reproducir" for n in ui().iter("node")), "Play control missing")
    click_text("Reproducir", "content-desc")
    wait(lambda: media_state("PLAYING"), "Player play icon failed")
    click_text("Detener", "content-desc")
    wait(lambda: not media_state("PLAYING"), "Stop failed")
    print("PASS: internal playback, background, media pause/resume and stop")
finally:
    adb("shell", "rm", "-f", REMOTE)
