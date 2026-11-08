import AppKit
import RouterKit
import SwiftUI

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        Task { @MainActor in
            await AppModel.shared.shutdown()
            sender.reply(toApplicationShouldTerminate: true)
        }
        return .terminateLater
    }
}

struct ZyxelRouterApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var model = AppModel.shared

    var body: some Scene {
        WindowGroup("Zyxel Router") {
            RootView()
                .environmentObject(model)
                .frame(minWidth: 620, minHeight: 520)
                .task { await model.start() }
        }
        .windowResizability(.contentMinSize)
    }
}

struct RootView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        switch model.phase {
        case .starting: ProgressView("Подключение к роутеру…").frame(maxWidth: .infinity, maxHeight: .infinity)
        case .login: LoginView()
        case .main: MainView()
        }
    }
}

struct LoginView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(spacing: 18) {
            Image(systemName: "wifi.router").font(.system(size: 52)).foregroundStyle(.tint)
            Text("Zyxel Router").font(.title.bold())
            Text(model.routerName.isEmpty ? "Роутер пока не найден" : model.routerName).foregroundStyle(.secondary)
            Form {
                TextField("Адрес роутера", text: $model.address)
                TextField("Пользователь", text: $model.username)
                SecureField("Пароль", text: $model.password).onSubmit(submit)
                Toggle("Запомнить пароль", isOn: $model.remember)
            }
            .frame(maxWidth: 360)
            if let error = model.error {
                Text(error).foregroundStyle(.red).multilineTextAlignment(.center).frame(maxWidth: 360)
            }
            Button(action: submit) {
                if model.busy { ProgressView().controlSize(.small) } else { Text("Войти").frame(minWidth: 120) }
            }
            .keyboardShortcut(.defaultAction)
            .disabled(model.busy || model.password.isEmpty || model.username.isEmpty)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task(id: model.address) { await model.probe() }
    }

    private func submit() { Task { await model.login() } }
}

struct MainView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(spacing: 0) {
            if let banner = model.banner {
                Text(banner).font(.callout).padding(8).frame(maxWidth: .infinity).background(.orange.opacity(0.25))
            }
            TabView(selection: $model.selectedTab) {
                OverviewView().tabItem { Text("Обзор") }.tag(0)
                DevicesView().tabItem { Text("Устройства") }.tag(1)
                WifiView().tabItem { Text("Wi-Fi") }.tag(2)
                ParentalView().tabItem { Text("Контроль") }.tag(3)
                RoutingView().tabItem { Text("Маршруты") }.tag(4)
                LogsView().tabItem { Text("Журнал") }.tag(5)
            }
            .padding(.top, 8)
        }
        .toolbar {
            ToolbarItem { Button { Task { await model.refresh() } } label: { Label("Обновить", systemImage: "arrow.clockwise") } }
            ToolbarItem {
                Menu {
                    Button("Скопировать диагностику без личных данных") {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(model.snapshot?.schema ?? "", forType: .string)
                    }
                    Button("Перезагрузить роутер…") { model.confirmReboot = true }
                    Divider()
                    Button("Выйти") { Task { await model.logout() } }
                } label: { Label("Ещё", systemImage: "ellipsis.circle") }
            }
        }
        .confirmationDialog("Перезагрузить роутер?", isPresented: $model.confirmReboot) {
            Button("Перезагрузить", role: .destructive) { Task { await model.reboot() } }
        } message: { Text("Интернет и Wi-Fi пропадут на несколько минут.") }
    }
}

struct OverviewView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        if let s = model.snapshot {
            Form {
                Section("Интернет") {
                    LabeledContent("Состояние") {
                        Label(s.internetUp ? "Подключён" : "Нет подключения", systemImage: "circle.fill")
                            .foregroundStyle(s.internetUp ? .green : .red)
                    }
                    LabeledContent("Внешний IP", value: s.wanIP.isEmpty ? "—" : s.wanIP)
                    LabeledContent("Загрузка", value: formatSpeed(s.downKbps))
                    LabeledContent("Отдача", value: formatSpeed(s.upKbps))
                    LabeledContent("Тип", value: [s.linkType, s.encapsulation].filter { !$0.isEmpty }.joined(separator: " · "))
                    LabeledContent("DNS", value: s.dns.isEmpty ? "—" : s.dns)
                    LabeledContent("Подключён", value: formatDuration(s.internetUptime))
                }
                Section("Сеть") {
                    LabeledContent("Устройств в сети", value: "\(s.devices.filter(\.online).count)")
                    LabeledContent("Включено сетей Wi-Fi", value: "\(s.wifi.filter(\.enabled).count) из \(s.wifi.count)")
                }
                Section("Роутер") {
                    LabeledContent("Модель", value: s.model)
                    LabeledContent("Прошивка", value: s.firmware)
                    LabeledContent("Работает", value: formatDuration(s.uptime))
                    if let updated = model.updated {
                        LabeledContent("Обновлено", value: updated.formatted(date: .omitted, time: .standard))
                    }
                }
            }
            .formStyle(.grouped)
        }
    }
}

// @State is a macro in this SDK and its plugin ships only with Xcode, so view state lives in an ObservableObject.
final class DeviceFilter: ObservableObject {
    @Published var query = ""
    @Published var onlineOnly = false   // по умолчанию показываем и офлайн-устройства
    @Published var selected: RouterDevice?
}

/// «был в сети: …» для офлайн-устройства из истории.
func lastSeenLabel(_ epoch: Int) -> String {
    guard epoch > 0 else { return "давно" }
    let date = Date(timeIntervalSince1970: TimeInterval(epoch))
    let f = RelativeDateTimeFormatter(); f.locale = Locale(identifier: "ru")
    return f.localizedString(for: date, relativeTo: Date())
}

struct DevicesView: View {
    @EnvironmentObject private var model: AppModel
    @StateObject private var filter = DeviceFilter()

    private var devices: [RouterDevice] {
        model.displayDevices.filter { d in
            (!filter.onlineOnly || d.online) && (filter.query.isEmpty || [d.name, d.ip, d.mac].contains { $0.localizedCaseInsensitiveContains(filter.query) })
        }
    }

    var body: some View {
        VStack {
            HStack {
                TextField("Поиск по имени, IP или MAC", text: $filter.query).textFieldStyle(.roundedBorder)
                Toggle("Только в сети", isOn: $filter.onlineOnly)
            }
            .padding(.horizontal)
            List(devices) { d in
                Button { filter.selected = d } label: {
                    HStack(spacing: 12) {
                        Image(systemName: d.typeIcon).foregroundStyle(d.online ? Color.accentColor : .secondary).frame(width: 24)
                        VStack(alignment: .leading, spacing: 2) {
                            HStack {
                                Text(d.name).font(.headline)
                                if d.guest { Text("гость").font(.caption).foregroundStyle(.orange) }
                                if d.blocked { Text("заблок.").font(.caption).foregroundStyle(.red) }
                            }
                            Text([d.type == "Неизвестно" ? "" : d.type, [d.ip, d.mac].filter { !$0.isEmpty }.joined(separator: " · ")].filter { !$0.isEmpty }.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        VStack(alignment: .trailing, spacing: 2) {
                            Text(d.online ? "В сети" : "Не в сети").font(.caption).foregroundStyle(d.online ? .green : .secondary)
                            Text(d.online ? (d.network.isEmpty ? d.connection : d.network) : "был: \(lastSeenLabel(d.lastSeen))").font(.caption).foregroundStyle(.secondary)
                        }
                        Image(systemName: "chevron.right").font(.caption2).foregroundStyle(.tertiary)
                    }
                    .contentShape(Rectangle())
                    .padding(.vertical, 2)
                }
                .buttonStyle(.plain)
            }
            .overlay { if devices.isEmpty { Text("Устройства не найдены").foregroundStyle(.secondary) } }
        }
        .sheet(item: $filter.selected) { DeviceDetailView(device: $0) { filter.selected = nil } }
    }
}

struct DeviceDetailView: View {
    let device: RouterDevice
    let onClose: () -> Void
    @EnvironmentObject private var model: AppModel

    private func row(_ label: String, _ value: String) -> some View {
        LabeledContent(label) { Text(value).textSelection(.enabled) }
    }

    var body: some View {
        let d = device
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: d.typeIcon).font(.largeTitle).foregroundStyle(.tint)
                VStack(alignment: .leading) {
                    Text(d.name).font(.title3.bold()).textSelection(.enabled)
                    Text(d.type).foregroundStyle(.secondary)
                }
                Spacer()
            }
            Form {
                Section("Сеть") {
                    row("Состояние", d.online ? "В сети" : "Не в сети")
                    if !d.online { row("Был в сети", lastSeenLabel(d.lastSeen)) }
                    row("Где", d.network.isEmpty ? d.connection : "\(d.network) (\(d.connection))")
                    row("IP-адрес", d.ip.isEmpty ? "—" : d.ip)
                    row("MAC", d.mac)
                    if d.ipv6 { row("IPv6", "есть") }
                    row("Адрес", d.staticIP ? "статический" : "по DHCP")
                    if d.blocked { row("Родительский контроль", "заблокирован") }
                }
                if d.connection.lowercased().contains("wi-fi") {
                    Section("Wi-Fi") {
                        if d.signal > 0 { row("Сигнал", String(repeating: "▮", count: d.signal) + String(repeating: "▯", count: max(0, 5 - d.signal))) }
                        if d.snr > 0 { row("Сигнал/шум", "\(d.snr) дБ") }
                        if d.phyRate > 0 { row("Скорость канала", "\(d.phyRate) Мбит/с") }
                        if !d.wifiStandard.isEmpty { row("Стандарт", "802.11\(d.wifiStandard)") }
                    }
                }
                Section("Трафик") {
                    row("Принято", formatBytes(d.bytesDown))
                    row("Отправлено", formatBytes(d.bytesUp))
                }
                if !d.vendorClass.isEmpty { Section("Производитель (DHCP)") { Text(d.vendorClass).textSelection(.enabled) } }
                Section {
                    DisclosureGroup("Все поля (\(d.details.count))") {
                        ForEach(d.details.sorted(by: { $0.key < $1.key }), id: \.key) { k, v in
                            row(k, v).font(.caption)
                        }
                    }
                }
            }
            .formStyle(.grouped)
            HStack {
                if !d.online { Button("Забыть", role: .destructive) { model.forgetDevice(d.mac); onClose() } }
                Spacer()
                Button("Закрыть", action: onClose).keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(width: 480, height: 560)
        .background(Color(nsColor: .windowBackgroundColor))
    }
}

final class LogFilter: ObservableObject {
    @Published var query = ""
    @Published var severity = ""      // пусто = все
    @Published var period = LogPeriod.day
    @Published var showStats = true
}

private func severityColor(_ s: String) -> Color {
    switch s {
    case "emerg", "alert", "crit", "err": return .red
    case "warning", "warn": return .orange
    case "debug": return .secondary
    default: return .primary
    }
}

struct LogsView: View {
    @EnvironmentObject private var model: AppModel
    @StateObject private var filter = LogFilter()

    private var entries: [LogEntry] {
        (model.log ?? []).filter { e in
            (filter.severity.isEmpty || e.severity == filter.severity)
                && (filter.query.isEmpty || e.raw.localizedCaseInsensitiveContains(filter.query))
        }
    }

    var body: some View {
        Group {
            if let all = model.log {
                VStack(spacing: 8) {
                    HStack {
                        Picker("", selection: $filter.showStats) { Text("Статистика").tag(true); Text("Записи").tag(false) }
                            .pickerStyle(.segmented).frame(width: 220)
                        Spacer()
                        Button { Task { await model.loadLog() } } label: { Image(systemName: "arrow.clockwise") }
                    }
                    .padding(.horizontal)
                    if filter.showStats {
                        statsView(all)
                    } else {
                        entriesView
                    }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task { if model.log == nil { await model.loadLog() } }
    }

    @ViewBuilder private func statsView(_ all: [LogEntry]) -> some View {
        List {
            Section("Всего записей: \(all.count)") {
                Picker("Период", selection: $filter.period) {
                    ForEach(LogPeriod.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                }.pickerStyle(.segmented)
                ForEach(logAggregate(all, by: filter.period)) { item in
                    LabeledContent(item.label) { Text("\(item.count)") }
                }
            }
            Section("По важности") {
                ForEach(logCountsBy(all) { $0.severity }) { item in
                    LabeledContent { Text("\(item.count)") } label: { Text(item.label).foregroundStyle(severityColor(item.label)) }
                }
            }
            Section("Категории") {
                ForEach(Array(logCountsBy(all) { $0.category }.prefix(10))) { item in
                    LabeledContent(item.label) { Text("\(item.count)") }
                }
            }
        }
    }

    private var entriesView: some View {
        VStack(spacing: 6) {
            HStack {
                TextField("Поиск по тексту", text: $filter.query).textFieldStyle(.roundedBorder)
                Picker("", selection: $filter.severity) {
                    Text("Все").tag("")
                    ForEach(["err", "warning", "notice", "info", "debug"], id: \.self) { Text($0).tag($0) }
                }.frame(width: 120)
            }
            .padding(.horizontal)
            List(entries) { e in
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(e.category).font(.caption).foregroundStyle(.tint)
                        Text(e.severity).font(.caption).foregroundStyle(severityColor(e.severity))
                        Text("\(logDayLabel(e)) \(e.time)").font(.caption).foregroundStyle(.secondary)
                    }
                    Text(e.message.isEmpty ? e.raw : e.message).font(.callout).textSelection(.enabled)
                }
            }
            .overlay { if entries.isEmpty { Text("Записей нет").foregroundStyle(.secondary) } }
        }
    }
}

struct WifiView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        List(model.snapshot?.wifi ?? []) { w in
            HStack(spacing: 12) {
                Image(systemName: w.enabled ? "wifi" : "wifi.slash").foregroundStyle(w.enabled ? Color.accentColor : .secondary).frame(width: 24)
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(w.ssid).font(.headline)
                        if w.guest { Text("гость").font(.caption).foregroundStyle(.orange) }
                    }
                    Text([w.band, w.channel.isEmpty ? "" : "канал \(w.channel)", w.security].filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Text(w.enabled ? "Включена" : "Выключена").font(.caption).foregroundStyle(w.enabled ? .green : .secondary)
            }
            .padding(.vertical, 2)
        }
    }
}

private let ruDays = ["Mon": "Пн", "Tue": "Вт", "Wed": "Ср", "Thu": "Чт", "Fri": "Пт", "Sat": "Сб", "Sun": "Вс"]
private func ruDaysLine(_ days: [String]) -> String { days.map { ruDays[$0] ?? $0 }.joined(separator: " ") }

struct ParentalView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Group {
            if let pc = model.parental {
                List {
                    Section {
                        Toggle(isOn: Binding(get: { pc.enabled }, set: { v in Task { await model.setParentalEnabled(v) } })) {
                            VStack(alignment: .leading) {
                                Text("Родительский контроль")
                                Text(pc.enabled ? "Включён" : "Выключен").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    Section {
                        if pc.profiles.isEmpty { Text("Профилей пока нет.").foregroundStyle(.secondary) }
                        ForEach(pc.profiles) { p in
                            HStack {
                                Toggle(isOn: Binding(get: { p.enabled }, set: { v in Task { await model.setProfileEnabled(index: p.index, on: v) } })) {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(p.name.isEmpty ? "Профиль \(p.index)" : p.name)
                                        Text("\(p.macs.count) устройств · \(ruDaysLine(p.days)) · \(p.times.joined(separator: ", "))").font(.caption).foregroundStyle(.secondary)
                                        if p.blocksSites || p.blocksServices {
                                            Text("Ограничивает: " + [p.blocksSites ? "сайты" : nil, p.blocksServices ? "сервисы" : nil].compactMap { $0 }.joined(separator: ", ")).font(.caption).foregroundStyle(.orange)
                                        }
                                    }
                                }
                                Spacer()
                                Button { model.openEditProfile(p) } label: { Image(systemName: "pencil") }.buttonStyle(.borderless)
                                Button { model.confirmDeleteIndex = p.index } label: { Image(systemName: "trash") }.buttonStyle(.borderless).foregroundStyle(.red)
                            }
                        }
                    } header: {
                        HStack {
                            Text("Профили")
                            Spacer()
                            Button { model.openNewProfile() } label: { Label("Добавить", systemImage: "plus") }.buttonStyle(.borderless)
                        }
                    }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task { await model.loadParental() }
        .sheet(isPresented: $model.editorOpen) { ProfileEditor() }
        .confirmationDialog("Удалить профиль?", isPresented: Binding(get: { model.confirmDeleteIndex != nil }, set: { if !$0 { model.confirmDeleteIndex = nil } })) {
            Button("Удалить", role: .destructive) { if let i = model.confirmDeleteIndex { model.confirmDeleteIndex = nil; Task { await model.deleteProfile(index: i) } } }
        }
    }
}

struct ProfileEditor: View {
    @EnvironmentObject private var model: AppModel
    private let dayCodes = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]

    private func deviceToggle(_ mac: String, _ label: String, online: Bool) -> some View {
        Toggle(isOn: Binding(get: { model.editMacs.contains(mac) }, set: { on in if on { model.editMacs.insert(mac) } else { model.editMacs.remove(mac) } })) {
            Text(label).font(.callout).foregroundStyle(online ? .primary : .secondary)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(model.editorIndex == nil ? "Новый профиль" : "Изменить профиль").font(.title3.bold())
            TextField("Название", text: $model.editName).textFieldStyle(.roundedBorder)

            Text("Устройства").font(.headline)
            // Отдельный прокручиваемый список чекбоксов фиксированной высоты — иначе форма
            // растягивается и кнопки уходят за нижний край.
            ScrollView {
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(model.displayDevices) { d in
                        deviceToggle(d.mac, "\(d.name) · \(d.mac)" + (d.online ? "" : " · не в сети"), online: d.online)
                    }
                    ForEach(model.editOfflineMacs, id: \.self) { mac in
                        deviceToggle(mac, "\(mac) · не в сети", online: false)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(8)
            }
            .frame(height: 170)
            .background(RoundedRectangle(cornerRadius: 8).fill(Color.gray.opacity(0.1)))
            HStack {
                TextField("Добавить MAC вручную", text: $model.editManualMac).textFieldStyle(.roundedBorder).onSubmit { model.addManualMac() }
                Button("Добавить") { model.addManualMac() }.disabled(model.editManualMac.isEmpty)
            }

            Text("Дни").font(.headline)
            HStack(spacing: 6) {
                ForEach(dayCodes, id: \.self) { code in
                    DayChip(label: ruDays[code] ?? code, selected: model.editDays.contains(code)) {
                        if model.editDays.contains(code) { model.editDays.remove(code) } else { model.editDays.insert(code) }
                    }
                }
            }
            Text(model.editDays.isEmpty ? "Дни не выбраны" : "Выбраны: " + ruDaysLine(dayCodes.filter { model.editDays.contains($0) }))
                .font(.caption).foregroundStyle(model.editDays.isEmpty ? .secondary : .primary)

            HStack {
                Text("Время:")
                TextField("с", text: $model.editStart).textFieldStyle(.roundedBorder).frame(width: 70)
                Text("—")
                TextField("до", text: $model.editEnd).textFieldStyle(.roundedBorder).frame(width: 70)
                Toggle("Включён", isOn: $model.editEnabled)
            }
            if model.editOriginalTimes.count > 1 {
                Text("У профиля несколько интервалов; сохранение оставит только первый.").font(.caption).foregroundStyle(.orange)
            }
            if let e = model.editError { Text(e).foregroundStyle(.red).font(.callout) }

            Spacer(minLength: 0)
            HStack {
                Button("Отмена") { model.editorOpen = false }
                Spacer()
                Button("Сохранить") { Task { await model.saveEditor() } }.keyboardShortcut(.defaultAction).disabled(model.editSaving)
            }
        }
        .padding(20)
        .frame(width: 470, height: 560)
        .background(Color(nsColor: .windowBackgroundColor))
    }
}

/// День недели в редакторе: явная заливка в выбранном состоянии, вся область кликабельна.
struct DayChip: View {
    let label: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.callout)
                .frame(minWidth: 32)
                .padding(.vertical, 6)
                .padding(.horizontal, 4)
                .background(selected ? Color.accentColor : Color.gray.opacity(0.22))
                .foregroundStyle(selected ? Color.white : Color.primary)
                .clipShape(RoundedRectangle(cornerRadius: 7))
        }
        .buttonStyle(.plain)
    }
}

struct RoutingView: View {
    @EnvironmentObject private var model: AppModel

    @ViewBuilder private func group(_ title: String, _ items: [RouteItem]) -> some View {
        if !items.isEmpty {
            Section(title) {
                ForEach(items) { r in
                    HStack(spacing: 12) {
                        Image(systemName: r.enabled ? "arrow.triangle.branch" : "nosign").foregroundStyle(r.enabled ? Color.accentColor : .secondary).frame(width: 20)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(r.title)
                            Text(r.subtitle).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Text(r.enabled ? "Активен" : "Выключен").font(.caption).foregroundStyle(r.enabled ? .green : .secondary)
                    }
                }
            }
        }
    }

    var body: some View {
        Group {
            if let r = model.routing {
                List {
                    if r.staticRoutes.isEmpty && r.dnsRoutes.isEmpty && r.policyRoutes.isEmpty && r.rip.isEmpty {
                        Text("Маршруты не настроены.").foregroundStyle(.secondary)
                    }
                    group("Статические маршруты", r.staticRoutes)
                    group("Маршруты DNS", r.dnsRoutes)
                    group("Маршруты по политикам", r.policyRoutes)
                    group("RIP", r.rip)
                    Text("Раздел только для чтения: изменение маршрутов может оборвать связь, добавим его отдельно и очень осторожно.").font(.caption).foregroundStyle(.secondary)
                }
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task { await model.loadRouting() }
    }
}
