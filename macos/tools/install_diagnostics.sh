#!/bin/zsh
# Устанавливает ежедневную диагностику роутера (launchd, по умолчанию в 08:00).
#   macos/tools/install_diagnostics.sh            — установить/обновить (08:00)
#   macos/tools/install_diagnostics.sh 7 30       — установить на 07:30
#   macos/tools/install_diagnostics.sh --run      — запустить сейчас (после установки)
#   macos/tools/install_diagnostics.sh --uninstall
# Пароль берётся из Связки ключей (его сохраняет приложение при входе с «Запомнить»).
set -e
LABEL=com.usharik.zyxelrouter.diagnostics
SUPPORT="$HOME/Library/Application Support/ZyxelRouter"
BIN="$SUPPORT/bin"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
DOMAIN="gui/$(id -u)"

if [[ "$1" == "--uninstall" ]]; then
  launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
  rm -f "$PLIST" "$BIN/routerdiag" "$BIN/routerdiag.sh"
  echo "Удалено. Отчёты оставлены в $SUPPORT/diagnostics"
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
ADDRESS=$(defaults read com.usharik.zyxelrouter address 2>/dev/null || echo 192.168.1.1)
export ROUTER_USER=$(defaults read com.usharik.zyxelrouter username 2>/dev/null || echo admin)
HOST=${ADDRESS%%:*}
for i in {1..18}; do /sbin/ping -q -c 1 -t 2 "$HOST" >/dev/null 2>&1 && break; sleep 5; done
export ROUTER_PASSWORD=$(/usr/bin/security find-generic-password -s com.usharik.zyxelrouter -a "$ROUTER_USER" -w 2>/dev/null)
exec "$(dirname "$0")/routerdiag" "$ADDRESS" > /dev/null
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

launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
launchctl bootstrap "$DOMAIN" "$PLIST"
printf "Установлено: ежедневно в %02d:%02d.\nОтчёты: %s/diagnostics/latest.md\nЗапустить сейчас: %s --run\n" "$HOUR" "$MINUTE" "$SUPPORT" "$0"
