#!/bin/zsh
# Копирует свежий debug-APK на Google Drive (папка синхронизируется десктопным клиентом).
# Сборка: (cd android && ./gradlew :app:assembleDebug), затем tools/publish_apk.sh
set -e
cd "$(dirname "$0")/.."
SRC=android/app/build/outputs/apk/debug/app-debug.apk
[ -f "$SRC" ] || { echo "нет $SRC: сначала соберите APK"; exit 1; }
# Куда класть APK. По умолчанию локальная папка dist/; для Google Drive задайте APK_DRIVE_DIR.
DRIVE=${APK_DRIVE_DIR:-"$(cd "$(dirname "$0")/.." && pwd)/dist"}
NAME=zyxel-router
mkdir -p "$DRIVE"
cp "$SRC" "$DRIVE/$NAME-latest.apk"
STAMP=$(date +%Y-%m-%d_%H%M); cp "$SRC" "$DRIVE/$NAME-$STAMP.apk"
# в архиве держим 5 последних датированных сборок
ls -1t "$DRIVE"/$NAME-2*.apk 2>/dev/null | tail -n +6 | while read f; do rm -f "$f"; done
echo "OK: $DRIVE/$NAME-latest.apk ($(du -h "$SRC" | cut -f1)), копия $STAMP"
