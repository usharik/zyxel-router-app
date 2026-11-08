#!/bin/zsh
# Builds Zyxel Router.app and installs it into /Applications.
set -e
cd "$(dirname "$0")"
swift build -c release --product ZyxelRouter
APP="build/Zyxel Router.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp .build/release/ZyxelRouter "$APP/Contents/MacOS/"
cp Resources/Info.plist "$APP/Contents/"
if [ ! -f Resources/AppIcon.icns ]; then
  swift tools/make_icon.swift build/AppIcon.iconset
  iconutil -c icns build/AppIcon.iconset -o Resources/AppIcon.icns
fi
cp Resources/AppIcon.icns "$APP/Contents/Resources/"
codesign --force --deep --sign - "$APP"
osascript -e 'quit app "Zyxel Router"' 2>/dev/null || true
rm -rf "/Applications/Zyxel Router.app"
cp -R "$APP" /Applications/
rm -rf "$APP"   # не оставляем копию-артефакт в проекте, иначе она дублируется в Launchpad
echo "Installed: /Applications/Zyxel Router.app"
