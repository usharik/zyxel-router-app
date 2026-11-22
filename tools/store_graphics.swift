import AppKit

// Графика карточки Google Play: значок 512×512 и обложка 1024×500 для каждого языка.
// Рисует тот же силуэт, что android/app/src/main/res/drawable/ic_router.xml (окно 108×108), на фоне icon_bg.
// Запуск из корня репозитория: swift tools/store_graphics.swift
// Результат: android/fastlane/metadata/android/<язык>/images/{icon,featureGraphic}.png

let root = FileManager.default.currentDirectoryPath + "/android/fastlane/metadata/android"
let brand = NSColor(red: 0x0B / 255.0, green: 0x5A / 255.0, blue: 0x85 / 255.0, alpha: 1)
let brandDark = NSColor(red: 0x06 / 255.0, green: 0x33 / 255.0, blue: 0x4D / 255.0, alpha: 1)

/// Подписи обложки: название как в title.txt и что умеет приложение.
let taglines: [String: String] = [
    "en-US": "Devices · Wi‑Fi · Parental control · Connection drops",
    "ru-RU": "Устройства · Wi‑Fi · Родительский контроль · Обрывы связи",
    "uk": "Пристрої · Wi‑Fi · Батьківський контроль · Обриви зв’язку",
    "cs-CZ": "Zařízení · Wi‑Fi · Rodičovská kontrola · Výpadky připojení",
    "de-DE": "Geräte · WLAN · Kindersicherung · Verbindungsabbrüche",
]

/// Силуэт роутера в координатах векторного значка (0…108, ось Y вниз), вписанный в квадрат `rect`.
func drawRouter(in rect: NSRect, color: NSColor) {
    let k = rect.width / 108
    func p(_ x: CGFloat, _ y: CGFloat) -> NSPoint { NSPoint(x: rect.minX + x * k, y: rect.maxY - y * k) }
    color.set()
    // Корпус со скруглением 5 и двумя «лампочками» (вырезы).
    let body = NSBezierPath(roundedRect: NSRect(x: rect.minX + 31 * k, y: rect.maxY - 80 * k, width: 46 * k, height: 18 * k), xRadius: 5 * k, yRadius: 5 * k)
    for cx in [41.0, 51.0] {
        body.append(NSBezierPath(ovalIn: NSRect(x: rect.minX + (cx - 3) * k, y: rect.maxY - 74 * k, width: 6 * k, height: 6 * k)))
    }
    body.windingRule = .evenOdd
    body.fill()
    // Антенна.
    NSBezierPath(rect: NSRect(x: rect.minX + 52 * k, y: rect.maxY - 62 * k, width: 4 * k, height: 18 * k)).fill()
    // Две дуги сигнала: из (44,42) в (64,42) радиусом 14 и из (38,35) в (70,35) радиусом 23.
    for (x1, x2, y, r) in [(44.0, 64.0, 42.0, 14.0), (38.0, 70.0, 35.0, 23.0)] {
        let half = (x2 - x1) / 2, cy = y + (r * r - half * half).squareRoot()
        let center = p((x1 + x2) / 2, cy)
        let angle = atan2(cy - y, half) * 180 / .pi   // угол от горизонтали до концов дуги
        let arc = NSBezierPath()
        arc.appendArc(withCenter: center, radius: r * k, startAngle: 180 - angle, endAngle: angle, clockwise: true)
        arc.lineWidth = 4 * k
        arc.lineCapStyle = .round
        arc.stroke()
    }
}

func render(width: Int, height: Int, to path: String, draw: (NSRect) -> Void) throws {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: width, pixelsHigh: height, bitsPerSample: 8, samplesPerPixel: 4,
                               hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    draw(NSRect(x: 0, y: 0, width: width, height: height))
    NSGraphicsContext.current = nil
    try FileManager.default.createDirectory(atPath: (path as NSString).deletingLastPathComponent, withIntermediateDirectories: true)
    try rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: path))
}

for (lang, tagline) in taglines.sorted(by: { $0.key < $1.key }) {
    let dir = "\(root)/\(lang)"
    let title = (try String(contentsOfFile: "\(dir)/title.txt", encoding: .utf8)).trimmingCharacters(in: .whitespacesAndNewlines)

    // Значок: квадрат без прозрачности и без своих скруглений — Play сам скругляет и добавляет тень.
    try render(width: 512, height: 512, to: "\(dir)/images/icon.png") { r in
        brand.setFill(); r.fill()
        drawRouter(in: r, color: .white)
    }

    // Обложка: градиент, силуэт слева, название и подпись справа. Важное — в центральной части (Play обрезает края).
    try render(width: 1024, height: 500, to: "\(dir)/images/featureGraphic.png") { r in
        NSGradient(starting: brand, ending: brandDark)!.draw(in: r, angle: -30)
        drawRouter(in: NSRect(x: 40, y: 50, width: 400, height: 400), color: .white)
        let style = NSMutableParagraphStyle(); style.lineBreakMode = .byWordWrapping
        let titleAttrs: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: 54, weight: .bold), .foregroundColor: NSColor.white, .paragraphStyle: style]
        let tagAttrs: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: 28, weight: .medium), .foregroundColor: NSColor.white.withAlphaComponent(0.85), .paragraphStyle: style]
        let textRect = NSRect(x: 420, y: 90, width: 560, height: 320)
        let t = NSAttributedString(string: title, attributes: titleAttrs), g = NSAttributedString(string: tagline, attributes: tagAttrs)
        let th = t.boundingRect(with: textRect.size, options: [.usesLineFragmentOrigin]).height
        let gh = g.boundingRect(with: textRect.size, options: [.usesLineFragmentOrigin]).height
        let top = textRect.midY + (th + 24 + gh) / 2
        t.draw(with: NSRect(x: textRect.minX, y: top - th, width: textRect.width, height: th), options: [.usesLineFragmentOrigin])
        g.draw(with: NSRect(x: textRect.minX, y: top - th - 24 - gh, width: textRect.width, height: gh), options: [.usesLineFragmentOrigin])
    }
    print("\(lang): icon.png, featureGraphic.png")
}
