"""On-device UI smoke test. Requires an unlocked device and an installed APK."""
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET


ADB = os.environ.get("ADB", "adb")
REMOTE = "/sdcard/radio-explorer-smoke.xml"


def adb(*args):
    return subprocess.check_output([ADB, *args], text=True)


def nodes():
    adb("shell", "uiautomator", "dump", REMOTE)
    return list(ET.fromstring(adb("shell", "cat", REMOTE)).iter("node"))


def wait_for(predicate, message):
    end = time.monotonic() + 55
    while time.monotonic() < end:
        tree = nodes()
        if predicate(tree):
            return tree
        time.sleep(1)
    raise AssertionError(message)


def has_text(tree, text):
    return any(n.get("text") == text for n in tree)


def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def press(text):
    tree = wait_for(lambda t: has_text(t, text), "Missing control: " + text)
    tap(next(n for n in tree if n.get("text") == text))


def ready():
    return wait_for(
        lambda t: any("emisoras · operativas" in n.get("text", "") for n in t),
        "Catalogue did not load",
    )


def scroll_to(text):
    for _ in range(25):
        tree = nodes()
        if has_text(tree, text):
            tap(next(n for n in tree if n.get("text") == text))
            return
        view = next(n for n in tree if n.get("scrollable") == "true")
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", view.get("bounds")))
        x = str((x1 + x2) // 2)
        adb("shell", "input", "swipe", x, str(y2 - 30), x, str(y1 + 30), "300")
    raise AssertionError("Could not scroll to: " + text)


def query(value):
    tree = nodes()
    edit = next(n for n in tree if n.get("class") == "android.widget.EditText")
    tap(edit)
    adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
    for _ in range(len(edit.get("text", ""))):
        adb("shell", "input", "keyevent", "KEYCODE_DEL")
    if value:
        adb("shell", "input", "text", value.replace(" ", "%s"))
    press("Buscar")


def run():
    adb("shell", "am", "force-stop", "com.radioexplorer")
    adb("shell", "am", "start", "-n", "com.radioexplorer/.MainActivity")
    tree = ready()
    assert has_text(tree, "100 emisoras · operativas según Radio Browser")
    press("Cargar más")
    tree = wait_for(
        lambda t: has_text(t, "200 emisoras · operativas según Radio Browser"),
        "Pagination did not append the next page",
    )
    star = next(n for n in tree if n.get("content-desc", "").startswith("Añadir a favoritos: "))
    station = star.get("content-desc").split(": ", 1)[1]
    tap(star)
    press("☆ Favoritos")
    wait_for(lambda t: has_text(t, station), "Favorite did not appear")

    adb("shell", "am", "force-stop", "com.radioexplorer")
    adb("shell", "am", "start", "-n", "com.radioexplorer/.MainActivity")
    ready()
    press("☆ Favoritos")
    tree = wait_for(lambda t: has_text(t, station), "Favorite did not survive restart")
    tap(next(n for n in tree if n.get("content-desc") == "Quitar de favoritos: " + station))
    wait_for(lambda t: not has_text(t, station), "Favorite was not removed")
    press("★ Favoritos")
    ready()

    query("RadioExplorerNoSuchStation938271")
    wait_for(
        lambda t: has_text(t, "Sin resultados. Prueba otro país, género o nombre."),
        "Empty search did not display an explicit message",
    )
    query("Radio 3")
    tree = ready()
    names = [n.get("text", "") for n in tree if n.get("text", "").startswith("Radio 3")]
    assert names, "Search did not return Radio 3"
    query("")
    ready()
    press("Todos los géneros")
    press("Jazz")
    tree = ready()
    assert any("jazz" in n.get("text", "").lower() for n in tree), "Genre filtering failed"
    press("Jazz")
    press("Todos los géneros")
    ready()
    press("España")
    press("Todo el mundo")
    ready()
    press("Todo el mundo")
    scroll_to("España")
    ready()
    print("PASS: paging, favorite persistence/removal, empty/name search, genre and country filters")


try:
    run()
finally:
    adb("shell", "rm", "-f", REMOTE)
