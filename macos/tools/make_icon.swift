import AppKit
// Draws the app icon into an .iconset folder given as the first argument.
let dir = CommandLine.arguments[1]
try FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
for (points, scale) in [(16,1),(16,2),(32,1),(32,2),(128,1),(128,2),(256,1),(256,2),(512,1),(512,2)] {
    let px = points * scale
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: px, pixelsHigh: px, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    let s = CGFloat(px), inset = s * 0.09, box = NSRect(x: inset, y: inset, width: s - 2 * inset, height: s - 2 * inset)
    NSGradient(colors: [NSColor(red: 0.05, green: 0.42, blue: 0.62, alpha: 1), NSColor(red: 0.02, green: 0.22, blue: 0.42, alpha: 1)])!
        .draw(in: NSBezierPath(roundedRect: box, xRadius: s * 0.18, yRadius: s * 0.18), angle: -90)
    let config = NSImage.SymbolConfiguration(pointSize: s * 0.42, weight: .medium).applying(.init(hierarchicalColor: .white))
    let symbol = NSImage(systemSymbolName: "wifi.router", accessibilityDescription: nil)!.withSymbolConfiguration(config)!
    symbol.draw(in: NSRect(x: (s - symbol.size.width) / 2, y: (s - symbol.size.height) / 2, width: symbol.size.width, height: symbol.size.height))
    NSGraphicsContext.current = nil
    let name = scale == 1 ? "icon_\(points)x\(points).png" : "icon_\(points)x\(points)@2x.png"
    try rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: "\(dir)/\(name)"))
}
