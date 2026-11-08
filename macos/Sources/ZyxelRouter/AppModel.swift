import Foundation
import RouterKit
import Security
import SwiftUI

enum Keychain {
    private static func query(_ account: String) -> [CFString: Any] {
        [kSecClass: kSecClassGenericPassword, kSecAttrService: "com.usharik.zyxelrouter", kSecAttrAccount: account]
    }
    static func read(_ account: String) -> String? {
        var q = query(account)
        q[kSecReturnData] = true
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
    static func save(_ password: String, account: String) {
        delete(account)
        var q = query(account)
        q[kSecValueData] = Data(password.utf8)
        SecItemAdd(q as CFDictionary, nil)
    }
    static func delete(_ account: String) { SecItemDelete(query(account) as CFDictionary) }
}

@MainActor
final class AppModel: ObservableObject {
    static let shared = AppModel()
    enum Phase { case starting, login, main }

    @Published var phase = Phase.starting
    @Published var address = UserDefaults.standard.string(forKey: "address") ?? "192.168.1.1"
    @Published var username = UserDefaults.standard.string(forKey: "username") ?? "admin"
    @Published var password = ""
    @Published var remember = true
    @Published var routerName = ""
    @Published var busy = false
    @Published var error: String?
    @Published var banner: String?
    @Published var snapshot: RouterSnapshot? { didSet { recordDevices() } }
    @Published var updated: Date?
    @Published var confirmReboot = false
    @Published var parental: ParentalControl?
    @Published var routing: Routing?
    @Published var log: [LogEntry]?
    @Published var selectedTab = 0   // явный выбор вкладки, иначе TabView сбрасывается при смене баннера
    @Published var sectionBusy = false
    // Состояние редактора профиля родительского контроля.
    @Published var editorOpen = false
    @Published var editorIndex: Int?
    @Published var editName = ""
    @Published var editMacs: Set<String> = []
    @Published var editDays: Set<String> = []
    @Published var editStart = "00:00"
    @Published var editEnd = "23:59"
    @Published var editEnabled = true
    @Published var editOriginalTimes: [String] = []
    @Published var editManualMac = ""
    @Published var editError: String?
    @Published var editSaving = false
    @Published var confirmDeleteIndex: Int?

    private var client: RouterClient?
    private var pollTask: Task<Void, Never>?
    private var rebootNoticeUntil = Date.distantPast

    // Background refreshes must not wipe the reboot notice while the router is going down.
    private func setBanner(_ text: String?) {
        if Date() >= rebootNoticeUntil { banner = text }
    }

    // Локальная история устройств: роутер забывает отключившиеся устройства, а мы их помним.
    struct KnownDevice: Codable { var mac: String; var name: String; var type: String; var connection: String; var lastSeen: Int }
    private var knownKey: String { "knownDevices:" + address }

    private func loadKnown() -> [String: KnownDevice] {
        guard let data = UserDefaults.standard.data(forKey: knownKey),
              let list = try? JSONDecoder().decode([KnownDevice].self, from: data) else { return [:] }
        return Dictionary(uniqueKeysWithValues: list.map { ($0.mac, $0) })
    }

    private func recordDevices() {
        guard let devices = snapshot?.devices, !devices.isEmpty else { return }
        var known = loadKnown()
        let now = Int(Date().timeIntervalSince1970)
        for d in devices where !d.mac.isEmpty {
            known[d.mac] = KnownDevice(mac: d.mac, name: d.name, type: d.type, connection: d.connection, lastSeen: now)
        }
        // не копим бесконечно: храним до 200 последних
        let trimmed = Array(known.values.sorted { $0.lastSeen > $1.lastSeen }.prefix(200))
        if let data = try? JSONEncoder().encode(trimmed) { UserDefaults.standard.set(data, forKey: knownKey) }
    }

    /// Устройства для показа: живые из роутера плюс известные ранее, которых сейчас нет (как офлайн).
    var displayDevices: [RouterDevice] {
        let live = snapshot?.devices ?? []
        let liveMacs = Set(live.map(\.mac))
        let offline = loadKnown().values
            .filter { !liveMacs.contains($0.mac) }
            .map { RouterDevice.offline(mac: $0.mac, name: $0.name, type: $0.type, connection: $0.connection, lastSeen: $0.lastSeen) }
        return live + offline.sorted { $0.name.lowercased() < $1.name.lowercased() }
    }

    func forgetDevice(_ mac: String) {
        var known = loadKnown()
        known[mac] = nil
        if let data = try? JSONEncoder().encode(Array(known.values)) { UserDefaults.standard.set(data, forKey: knownKey) }
        objectWillChange.send()
    }

    func start() async {
        await probe()
        if let saved = Keychain.read(username) {
            password = saved
            await login()
        } else {
            phase = .login
        }
    }

    func probe() async {
        guard let c = try? RouterClient(address: address), let info = try? await c.basicInfo() else { routerName = ""; return }
        routerName = "\(info.model) · \(info.firmware)"
    }

    func login() async {
        busy = true
        error = nil
        defer { busy = false }
        do {
            let c = try RouterClient(address: address)
            try await c.login(username: username, password: password)
            client = c
            UserDefaults.standard.set(address, forKey: "address")
            UserDefaults.standard.set(username, forKey: "username")
            if remember { Keychain.save(password, account: username) } else { Keychain.delete(username) }
            snapshot = try await c.snapshot()
            updated = Date()
            banner = nil
            phase = .main
            startPolling()
        } catch {
            handleLoginFailure(error)
        }
    }

    // Saved credentials are dropped after a rejected login, so the app can never cause a router lockout by retrying.
    private func handleLoginFailure(_ failure: Error) {
        if case RouterError.invalidCredentials = failure {
            Keychain.delete(username)
            password = ""
        }
        stopPolling()
        client = nil
        snapshot = nil
        // Сбрасываем данные другого роутера, чтобы после перелогина не мелькали чужие профили/маршруты.
        parental = nil
        routing = nil
        editorOpen = false
        error = failure.localizedDescription
        phase = .login
    }

    private func startPolling() {
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(15))
                if Task.isCancelled { return }
                await self?.refresh()
            }
        }
    }

    private func stopPolling() {
        pollTask?.cancel()
        pollTask = nil
    }

    func refresh() async {
        guard let client else { return }
        do {
            snapshot = try await client.snapshot()
            updated = Date()
            setBanner(nil)
        } catch RouterError.sessionExpired {
            await relogin(client)
        } catch {
            setBanner(error.localizedDescription)
        }
    }

    private func relogin(_ c: RouterClient) async {
        guard !password.isEmpty else { handleLoginFailure(RouterError.sessionExpired); return }
        do {
            try await c.login(username: username, password: password)
            snapshot = try await c.snapshot()
            updated = Date()
            setBanner(nil)
        } catch RouterError.invalidCredentials {
            handleLoginFailure(RouterError.invalidCredentials)
        } catch RouterError.locked(let seconds) {
            handleLoginFailure(RouterError.locked(seconds))
        } catch {
            setBanner(error.localizedDescription)
        }
    }

    func loadParental() async {
        guard let client else { return }
        sectionBusy = true
        defer { sectionBusy = false }
        do { parental = try await client.parentalControl(); setBanner(nil) }
        catch { setBanner(error.localizedDescription) }
    }

    func loadRouting() async {
        guard let client else { return }
        sectionBusy = true
        defer { sectionBusy = false }
        do { routing = try await client.routing(); setBanner(nil) }
        catch { setBanner(error.localizedDescription) }
    }

    func loadLog(security: Bool = false) async {
        guard let client else { return }
        sectionBusy = true
        defer { sectionBusy = false }
        do { log = try await client.systemLog(security: security); setBanner(nil) }
        catch { setBanner(error.localizedDescription) }
    }

    func setParentalEnabled(_ on: Bool) async {
        guard let client else { return }
        do {
            try await client.setParentalEnabled(on)
            parental = try await client.parentalControl()
            banner = nil
        } catch { banner = error.localizedDescription; await loadParental() }
    }

    func setProfileEnabled(index: Int, on: Bool) async {
        guard let client else { return }
        do {
            try await client.setProfileEnabled(index: index, on: on)
            parental = try await client.parentalControl()
            banner = nil
        } catch { banner = error.localizedDescription; await loadParental() }
    }

    func openNewProfile() {
        editorIndex = nil
        editName = ""
        editMacs = []
        editDays = []
        editStart = "00:00"
        editEnd = "23:59"
        editEnabled = true
        editError = nil
        editorOpen = true
    }

    func openEditProfile(_ p: ParentalProfile) {
        editorIndex = p.index
        editName = p.name
        // Профиль хранит MAC с дефисами; устройства — с двоеточиями. Сопоставляем по нормализованному виду.
        let wanted = Set(p.macs.map { $0.replacingOccurrences(of: "-", with: ":") })
        editMacs = Set((snapshot?.devices ?? []).map(\.mac).filter { wanted.contains($0) })
        for m in wanted where !editMacs.contains(m) { editMacs.insert(m) }
        editDays = Set(p.days)
        editOriginalTimes = p.times
        let range = (p.times.first ?? "00:00-23:59").split(separator: "-").map(String.init)
        editStart = range.first.flatMap(normalizeTime) ?? "00:00"
        editEnd = (range.count > 1 ? normalizeTime(range[1]) : nil) ?? "23:59"
        editEnabled = p.enabled
        editError = nil
        editorOpen = true
    }

    /// Ручное добавление MAC (для устройства, которого сейчас нет в сети).
    func addManualMac() {
        guard let mac = normalizeMAC(editManualMac) else { editError = "MAC в формате AA:BB:CC:DD:EE:FF."; return }
        editMacs.insert(mac)
        editManualMac = ""
        editError = nil
    }

    /// MAC из профиля, которых нет даже в истории устройств (добавлены вручную) — показываем отдельной строкой.
    var editOfflineMacs: [String] {
        let shown = Set(displayDevices.map(\.mac))
        return editMacs.subtracting(shown).sorted()
    }

    func saveEditor() async {
        guard let client else { return }
        let name = editName.trimmingCharacters(in: .whitespaces)
        let days = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].filter { editDays.contains($0) }
        if name.isEmpty { editError = "Укажите название профиля."; return }
        if editMacs.isEmpty { editError = "Выберите хотя бы одно устройство."; return }
        if editMacs.count > 32 { editError = "Не больше 32 устройств."; return }
        if days.isEmpty { editError = "Выберите хотя бы один день."; return }
        // Время сравнивается числами (минуты), а не строками, и дополняется ведущими нулями.
        guard let start = normalizeTime(editStart), let end = normalizeTime(editEnd),
              let sm = minutesOfTime(start), let em = minutesOfTime(end) else {
            editError = "Время в формате ЧЧ:ММ."; return
        }
        guard sm < em else { editError = "Начало должно быть раньше конца. Ночной интервал задайте двумя профилями."; return }
        let single = "\(start)-\(end)"
        // Если у профиля было несколько интервалов и первый не менялся — сохраняем все, иначе один.
        let timeRangeList = (editOriginalTimes.count > 1 && editOriginalTimes.first == single) ? editOriginalTimes.joined(separator: ",") : single
        editSaving = true
        defer { editSaving = false }
        do {
            try await client.saveProfile(index: editorIndex, name: name, macs: Array(editMacs), days: days, timeRangeList: timeRangeList, enabled: editEnabled)
        } catch {
            editError = error.localizedDescription   // запись не прошла — редактор открыт, можно повторить
            return
        }
        // Запись прошла. Ошибку перечитывания не показываем в редакторе, иначе повтор создаст дубль.
        editorOpen = false
        do { parental = try await client.parentalControl(); banner = nil }
        catch { banner = error.localizedDescription }
    }

    func deleteProfile(index: Int) async {
        guard let client else { return }
        do {
            try await client.deleteProfile(index: index)
            parental = try await client.parentalControl()
            banner = nil
        } catch { banner = error.localizedDescription }
    }

    func reboot() async {
        guard let client else { return }
        do {
            try await client.reboot()
            banner = "Роутер перезагружается. Интернет вернётся через несколько минут."
            rebootNoticeUntil = Date().addingTimeInterval(180)
        } catch {
            banner = error.localizedDescription
        }
    }

    func logout() async {
        stopPolling()
        await client?.logout()
        client = nil
        Keychain.delete(username)
        password = ""
        snapshot = nil
        parental = nil
        routing = nil
        editorOpen = false
        phase = .login
    }

    func shutdown() async {
        stopPolling()
        await client?.logout()
    }
}

func formatDuration(_ seconds: Int) -> String {
    guard seconds > 0 else { return "—" }
    let d = seconds / 86400, h = seconds % 86400 / 3600, m = seconds % 3600 / 60
    return (d > 0 ? "\(d) д " : "") + (d > 0 || h > 0 ? "\(h) ч " : "") + "\(m) мин"
}

func formatSpeed(_ kbps: Int) -> String {
    kbps > 0 ? String(format: "%.1f Мбит/с", Double(kbps) / 1000) : "—"
}
