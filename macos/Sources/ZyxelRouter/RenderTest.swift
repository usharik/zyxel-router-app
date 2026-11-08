#if DEBUG
import AppKit
import SwiftUI

/// Debug builds only: logs in without the Keychain and renders each screen to a PNG, with no visible window.
func runRenderTest(into dir: String) {
    let env = ProcessInfo.processInfo.environment
    let app = NSApplication.shared
    app.setActivationPolicy(.prohibited)
    Task { @MainActor in
        let model = AppModel.shared
        model.address = env["ZYXEL_ADDRESS"] ?? "127.0.0.1:18443"
        model.username = "admin"
        model.password = env["ZYXEL_PASSWORD"] ?? ""
        model.remember = false
        await model.probe()
        await model.login()
        await model.loadParental()
        await model.loadLog()
        model.openNewProfile()
        model.editName = "Дети"
        if let d = model.snapshot?.devices.first { model.editMacs = [d.mac] }
        model.editDays = ["Mon", "Tue", "Wed"]
        model.editStart = "21:00"; model.editEnd = "23:00"
        print("phase:", model.phase, "error:", model.error ?? "none", "devices:", model.snapshot?.devices.count ?? -1)
        let screens: [(String, AnyView)] = [
            ("login", AnyView(LoginView())), ("overview", AnyView(OverviewView())),
            ("devices", AnyView(DevicesView())), ("wifi", AnyView(WifiView())),
            ("parental", AnyView(ParentalView())), ("routing", AnyView(RoutingView())),
            ("editor", AnyView(ProfileEditor())),
            ("logs", AnyView(LogsView())),
            ("device", AnyView(DeviceDetailView(device: model.snapshot?.devices.first(where: { $0.type != "Неизвестно" }) ?? model.snapshot!.devices[0], onClose: {}))),
        ]
        for (name, view) in screens {
            let host = NSHostingView(rootView: view.environmentObject(model).frame(width: 640, height: 560))
            let window = NSWindow(contentRect: NSRect(x: -4000, y: -4000, width: 640, height: 560), styleMask: .borderless, backing: .buffered, defer: false)
            window.contentView = host
            window.orderBack(nil)
            try? await Task.sleep(for: .seconds(1))
            host.layoutSubtreeIfNeeded()
            if let rep = host.bitmapImageRepForCachingDisplay(in: host.bounds) {
                host.cacheDisplay(in: host.bounds, to: rep)
                try? rep.representation(using: .png, properties: [:])?.write(to: URL(fileURLWithPath: "\(dir)/\(name).png"))
            }
            window.orderOut(nil)
        }
        await model.shutdown()
        exit(0)
    }
    app.run()
}
#endif
