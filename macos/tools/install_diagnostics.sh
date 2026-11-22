#!/bin/zsh
# Устанавливает ежедневную диагностику роутера (launchd, по умолчанию в 08:00) и учёт обрывов связи
# (каждые 20 минут: журнал роутера хранит всего несколько часов, реже обрывы терялись бы).
#   macos/tools/install_diagnostics.sh            — установить/обновить (08:00)
#   macos/tools/install_diagnostics.sh 7 30       — установить на 07:30
#   macos/tools/install_diagnostics.sh --run      — запустить сейчас (после установки)
#   macos/tools/install_diagnostics.sh --run-drops — проверить обрывы сейчас
#   macos/tools/install_diagnostics.sh --uninstall
# Пароль берётся из Связки ключей (его сохраняет приложение при входе с «Запомнить»).
set -e
LABEL=com.usharik.routermanager.diagnostics
DROPS_LABEL=com.usharik.routermanager.drops
DROPS_INTERVAL=1200   # секунд между проверками обрывов
SUPPORT="$HOME/Library/Application Support/ZyxelRouter"
BIN="$SUPPORT/bin"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
DROPS_PLIST="$HOME/Library/LaunchAgents/$DROPS_LABEL.plist"
DOMAIN="gui/$(id -u)"
OLD_LABEL=com.usharik.zyxelrouter.diagnostics   # имя задания до 1.4
# Старое задание снимаем при любой команде, кроме --run: иначе диагностика пошла бы дважды.
if [[ "$1" != --run* ]]; then
  launchctl bootout "$DOMAIN/$OLD_LABEL" 2>/dev/null || true
  rm -f "$HOME/Library/LaunchAgents/$OLD_LABEL.plist"
fi

if [[ "$1" == "--uninstall" ]]; then
  launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
  launchctl bootout "$DOMAIN/$DROPS_LABEL" 2>/dev/null || true
  rm -f "$PLIST" "$DROPS_PLIST" "$BIN/routerdiag" "$BIN/routerdiag.sh"
  echo "Удалено. Отчёты и история обрывов оставлены в $SUPPORT/diagnostics"
  exit 0
fi
if [[ "$1" == "--run-drops" ]]; then
  launchctl kickstart -p "$DOMAIN/$DROPS_LABEL" && echo "Запущено; новые обрывы появятся в $SUPPORT/diagnostics/drops.log"
  exit 0
fi
if [[ "$1" == "--run" ]]; then
  launchctl kickstart -p "$DOMAIN/$LABEL" && echo "Запущено; отчёт появится в $SUPPORT/diagnostics/latest.md"
  exit 0
fi

HOUR=${1:-8}
MINUTE=${2:-0}
cd "$(dirname "$0")/.."
swift build -c release --product routerdiag
mkdir -p "$BIN" "$SUPPORT/diagnostics"
cp .build/release/routerdiag "$BIN/routerdiag"
codesign --force --sign - "$BIN/routerdiag"

cat > "$BIN/routerdiag.sh" <<'EOF'
#!/bin/zsh
# Обёртка для launchd: ждёт сеть после пробуждения, берёт пароль из Связки ключей и запускает диагностику.
# Аргументы передаются в routerdiag; с --drops-only это короткая проверка обрывов связи раз в 20 минут.
# До 1.4 приложение называлось com.usharik.zyxelrouter: если под новым именем ничего нет, берём старое.
setting() { defaults read com.usharik.routermanager "$1" 2>/dev/null || defaults read com.usharik.zyxelrouter "$1" 2>/dev/null; }
ADDRESS=$(setting address || echo 192.168.1.1)
export ROUTER_USER=$(setting username || echo admin)
HOST=${ADDRESS%%:*}
# Частая проверка не ждёт сеть полторы минуты: не дома или роутер недоступен — routerdiag сам тихо выйдет.
[[ "$1" == "--drops-only" ]] && TRIES=3 || TRIES=18
for i in {1..$TRIES}; do /sbin/ping -q -c 1 -t 2 "$HOST" >/dev/null 2>&1 && break; sleep 5; done
# Журнал частых проверок не растёт бесконечно: оставляем хвост (launchd дописывает в тот же файл).
LOG="$(dirname "$0")/../diagnostics/drops-monitor.log"
if [[ "$1" == "--drops-only" && -f "$LOG" && $(stat -f%z "$LOG") -gt 1000000 ]]; then
  tail -c 200000 "$LOG" > "$LOG.tmp" && cat "$LOG.tmp" > "$LOG"; rm -f "$LOG.tmp"
fi
# Чтение из Связки ключей может вызвать окно «разрешить доступ» (запись создана приложением, а читает /usr/bin/security).
# Ответ «Всегда разрешать» сохраняется: подпись security постоянна. Без ответа задание не должно висеть, и каждые
# 20 минут не должно появляться новое окно: на чтение даётся 30 с, затем проверка пропускается.
keychain() { /usr/bin/perl -e 'alarm 30; exec @ARGV' /usr/bin/security find-generic-password -s "$1" -a "$ROUTER_USER" -w 2>/dev/null; }
# Сначала запись старого имени: если она есть, доступ к ней у security уже разрешён и окна не будет.
export ROUTER_PASSWORD=$(keychain com.usharik.zyxelrouter || keychain com.usharik.routermanager)
exec "$(dirname "$0")/routerdiag" "$ADDRESS" "$@" > /dev/null
EOF
chmod +x "$BIN/routerdiag.sh"

cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array><string>/bin/zsh</string><string>$BIN/routerdiag.sh</string></array>
  <!-- Если в это время Mac спал, launchd запустит задание при пробуждении. -->
  <key>StartCalendarInterval</key>
  <dict><key>Hour</key><integer>$HOUR</integer><key>Minute</key><integer>$MINUTE</integer></dict>
  <key>StandardErrorPath</key><string>$SUPPORT/diagnostics/routerdiag.log</string>
  <key>ProcessType</key><string>Background</string>
  <key>LowPriorityIO</key><true/>
</dict>
</plist>
EOF

cat > "$DROPS_PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$DROPS_LABEL</string>
  <key>ProgramArguments</key>
  <array><string>/bin/zsh</string><string>$BIN/routerdiag.sh</string><string>--drops-only</string></array>
  <!-- Пропущенные во сне запуски launchd сводит в один при пробуждении. -->
  <key>StartInterval</key><integer>$DROPS_INTERVAL</integer>
  <key>RunAtLoad</key><true/>
  <key>StandardErrorPath</key><string>$SUPPORT/diagnostics/drops-monitor.log</string>
  <key>ProcessType</key><string>Background</string>
  <key>LowPriorityIO</key><true/>
</dict>
</plist>
EOF

launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
launchctl bootstrap "$DOMAIN" "$PLIST"
launchctl bootout "$DOMAIN/$DROPS_LABEL" 2>/dev/null || true
launchctl bootstrap "$DOMAIN" "$DROPS_PLIST"
printf "Установлено: диагностика ежедневно в %02d:%02d, обрывы связи — каждые %d минут.\nОтчёты: %s/diagnostics/latest.md\nОбрывы: %s/diagnostics/drops.log (история — drops.json)\nЗапустить сейчас: %s --run (обрывы: --run-drops)\n" "$HOUR" "$MINUTE" $((DROPS_INTERVAL / 60)) "$SUPPORT" "$SUPPORT" "$0"
