#!/usr/bin/env python3
"""Скриншоты телефона для карточки Google Play на пяти языках, в демо-режиме и без рекламы.

Нужно: запущенный эмулятор (образ google_apis, нужен adb root), установленная debug-сборка
(`./gradlew :app:installDebug`), ANDROID_SERIAL с серийным номером эмулятора (иначе скрипт откажется работать,
чтобы не трогать телефон). Запуск из корня репозитория:
  ANDROID_SERIAL=emulator-5600 python3 tools/store_screenshots.py
Результат: android/fastlane/metadata/android/<язык>/images/phoneScreenshots/1.png … 5.png.

Как устроено: язык системы меняется через persist.sys.locale с перезапуском оболочки, экран на время съёмки
переводится в 1080×2160 (Play требует сторону не длиннее двух других), строка состояния — в демо-режим
(одинаковые часы, полный заряд). Приложение запускается с `--ez screenshots true`: в debug-сборке это убирает
рекламу (ScreenshotMode в MainActivity). Кнопки ищутся по тексту из strings.xml нужного языка.
"""
import os, re, subprocess, sys, time, xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "android/app/src/main/res")
META = os.path.join(ROOT, "android/fastlane/metadata/android")
PKG = "com.usharik.routermanager"
# Язык карточки в fastlane → язык системы и папка values.
LOCALES = [("en-US", "en-US", "values"), ("ru-RU", "ru-RU", "values-ru"), ("uk", "uk-UA", "values-uk"),
           ("cs-CZ", "cs-CZ", "values-cs"), ("de-DE", "de-DE", "values-de")]

if not os.environ.get("ANDROID_SERIAL", "").startswith("emulator-"):
    sys.exit("Задайте ANDROID_SERIAL=emulator-NNNN: скрипт меняет язык и экран устройства.")
ADB = os.path.expanduser(os.environ.get("ADB", "~/Library/Android/sdk/platform-tools/adb"))


def adb(*args, check=True):
    return subprocess.run([ADB, *args], capture_output=True, text=True, check=check).stdout


def strings(folder):
    tree = ET.parse(os.path.join(RES, folder, "strings.xml"))
    return {e.get("name"): (e.text or "").replace("\\'", "'") for e in tree.getroot().iter("string")}


def ui_nodes():
    xml = adb("exec-out", "uiautomator", "dump", "/dev/tty", check=False)
    return re.findall(r'text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)


def tap_text(text, timeout=20):
    deadline = time.time() + timeout
    while time.time() < deadline:
        for t, x1, y1, x2, y2 in ui_nodes():
            if t == text:
                adb("shell", "input", "tap", str((int(x1) + int(x2)) // 2), str((int(y1) + int(y2)) // 2))
                return
        time.sleep(1)
    sys.exit(f"Не найдена кнопка «{text}» на экране")


def wait_ready():
    """После перезапуска оболочки ждём, пока снова заработают менеджер пакетов и окна."""
    time.sleep(5)
    for _ in range(90):
        if "package" in adb("shell", "service", "check", "package", check=False) and adb("shell", "pidof", "com.android.systemui", check=False).strip():
            time.sleep(8)
            return
        time.sleep(2)
    sys.exit("Эмулятор не поднялся после смены языка")


def status_bar_demo():
    adb("shell", "settings", "put", "global", "sysui_demo_allowed", "1")
    for extra in (["-e", "command", "enter"], ["-e", "command", "clock", "-e", "hhmm", "1000"],
                  ["-e", "command", "battery", "-e", "level", "100", "-e", "plugged", "false"],
                  ["-e", "command", "network", "-e", "wifi", "show", "-e", "level", "4", "-e", "mobile", "hide"],
                  ["-e", "command", "notifications", "-e", "visible", "false"]):
        adb("shell", "am", "broadcast", "-a", "com.android.systemui.demo", *extra)


def ensure_size():
    """Размер экрана 1080×2160; после перезапуска оболочки он может сброситься."""
    if "Override size: 1080x2160" not in adb("shell", "wm", "size"):
        adb("shell", "wm", "size", "1080x2160")
        time.sleep(4)


def shot(path):
    png = subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True, check=True).stdout
    width, height = int.from_bytes(png[16:20], "big"), int.from_bytes(png[20:24], "big")   # заголовок IHDR
    if max(width, height) > 2 * min(width, height):
        sys.exit(f"Скриншот {width}×{height}: Play требует сторону не длиннее двух других")
    with open(path, "wb") as f:
        f.write(png)


def main():
    if "DEBUGGABLE" not in adb("shell", "dumpsys", "package", PKG, check=False):
        sys.exit("Нужна установленная debug-сборка (./gradlew :app:installDebug): в release реклама не убирается.")
    adb("root"); time.sleep(3); adb("wait-for-device")
    adb("shell", "wm", "size", "1080x2160")
    try:
        for i, (lang, system, folder) in enumerate(LOCALES):
            s = strings(folder)
            adb("shell", "setprop", "persist.sys.locale", system)
            adb("shell", "setprop", "ctl.restart", "zygote")
            wait_ready()
            adb("shell", "wm", "size", "1080x2160")
            status_bar_demo()
            ensure_size()   # до запуска: смена размера перестраивает окна, в том числе форму согласия
            if i == 0:
                adb("shell", "pm", "clear", PKG)   # с чистого листа: без сохранённого адреса и истории
            adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--ez", "screenshots", "true")
            # Тестовая форма согласия Google (только debug) появляется один раз и не сразу; ответ сохраняется.
            deadline, relaunch = time.time() + 60, time.time() + 10
            while time.time() < deadline:
                texts = [t for t, *_ in ui_nodes()]
                if time.time() > relaunch and s["demo_try"] not in texts and not any("Publisher Test Ads" in t for t in texts):
                    # Сразу после перезапуска оболочки запуск иногда теряется: открылся рабочий стол.
                    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--ez", "screenshots", "true")
                    relaunch = time.time() + 10
                if "Do not consent" in texts:
                    tap_text("Do not consent"); time.sleep(3); continue
                if any(t.startswith("Welcome to Publisher Test Ads") for t in texts):
                    adb("shell", "input", "swipe", "540", "1600", "540", "600", "300"); time.sleep(1); continue
                if s["demo_try"] in texts:
                    time.sleep(3)   # форма могла ещё не успеть появиться поверх экрана входа
                    if "Do not consent" in [t for t, *_ in ui_nodes()]:
                        continue
                    break
                time.sleep(1)
            tap_text(s["demo_try"]); time.sleep(5)
            out = os.path.join(META, lang, "images", "phoneScreenshots")
            os.makedirs(out, exist_ok=True)
            for n, tab in enumerate(["tab_overview", "tab_devices", "tab_wifi", "tab_parental", "tab_log"], start=1):
                tap_text(s[tab]); time.sleep(4)
                ensure_size()
                shot(os.path.join(out, f"{n}.png"))
            adb("shell", "am", "force-stop", PKG)
            print(f"{lang}: 5 скриншотов")
    finally:
        adb("shell", "wm", "size", "reset", check=False)
        adb("shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", "exit", check=False)


if __name__ == "__main__":
    main()
