"""On-device UI smoke test. Requires an unlocked device and an installed APK."""
import os
import json
import re
import subprocess
import time
import urllib.parse
import urllib.request
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
        lambda t: any("emisoras cargadas · operativas" in n.get("text", "") for n in t),
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


def outside_first_page():
    base = "https://de1.api.radio-browser.info/json/stations/search?"
    def search(**kwargs):
        params = dict(countrycode="ES", hidebroken="true", order="clickcount", reverse="true", **kwargs)
        request = urllib.request.Request(base + urllib.parse.urlencode(params), headers={"User-Agent": "RadioExplorer/1.3"})
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    first = {s["name"] for s in search(limit=100)}
    for station in search(limit=100, offset=200):
        name = station["name"]
        if name not in first and re.fullmatch(r"[A-Za-z0-9 ]{8,30}", name):
            found = search(name=name, limit=1)
            if found and found[0]["name"] == name:
                return name
    raise AssertionError("Could not find a searchable station outside the first page")


def swipe_list():
    tree = nodes()
    view = next(n for n in tree if n.get("class") == "android.widget.ListView")
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", view.get("bounds")))
    x = str((x1 + x2) // 2)
    adb("shell", "input", "swipe", x, str(y2 - 30), x, str(y1 + 30), "180")


def run():
    adb("shell", "am", "force-stop", "com.radioexplorer")
    adb("shell", "am", "start", "-n", "com.radioexplorer/.MainActivity")
    tree = ready()
    assert has_text(tree, "100 emisoras cargadas · operativas según Radio Browser")
    assert not has_text(tree, "Mostrar más emisoras")
    tap(next(n for n in tree if n.get("content-desc") == "Información"))
    wait_for(lambda t: has_text(t, "Radio Explorer") and has_text(t, "LICENCIA LIBVLC"),
             "Information icon did not open the information dialog")
    press("ACEPTAR")
    deep_name = outside_first_page()
    query(deep_name)
    wait_for(lambda t: has_text(t, deep_name), "Search did not find a station outside the first page")
    query("")
    ready()
    for _ in range(35):
        tree = nodes()
        if any(re.match(r"^(?:2\d\d|[3-9]\d\d) emisoras cargadas", n.get("text", "")) for n in tree):
            break
        swipe_list()
    else:
        raise AssertionError("Infinite scroll did not append the next page")
    star = next(n for n in tree if n.get("content-desc", "").startswith("Añadir a favoritos: "))
    station = star.get("content-desc").split(": ", 1)[1]
    tap(star)
    press("Favoritos")
    wait_for(lambda t: has_text(t, station), "Favorite did not appear")

    adb("shell", "am", "force-stop", "com.radioexplorer")
    adb("shell", "am", "start", "-n", "com.radioexplorer/.MainActivity")
    ready()
    press("Favoritos")
    tree = wait_for(lambda t: has_text(t, station), "Favorite did not survive restart")
    tap(next(n for n in tree if n.get("content-desc") == "Quitar de favoritos: " + station))
    wait_for(lambda t: not has_text(t, station), "Favorite was not removed")
    press("Ver todas")
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
