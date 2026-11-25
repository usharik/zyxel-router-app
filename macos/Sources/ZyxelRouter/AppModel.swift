import Foundation
import RouterKit
import Security
import SwiftUI

enum Keychain {
    static let service = "com.usharik.routermanager"
    private static func query(_ account: String, service: String = service) -> [CFString: Any] {
        [kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: account]
    }
    static func read(_ account: String, service: String = service) -> String? {
        var q = query(account, service: service)
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

/// До 1.4 приложение называлось com.usharik.zyxelrouter (идентификатор, настройки и Связка ключей).
/// Один раз переносит адрес, логин, историю устройств и сохранённый пароль под новое имя; старые записи не трогает,
/// чтобы ими могла пользоваться ранее установленная диагностика.
enum LegacyMigration {
    static let oldId = "com.usharik.zyxelrouter"
    private static let doneKey = "migratedFromZyxelrouter"

    static func run(defaults: UserDefaults = .standard) {
        guard !defaults.bool(forKey: doneKey) else { return }
        defer { defaults.set(true, forKey: doneKey) }
        guard let old = defaults.persistentDomain(forName: oldId) else { return }
        for (key, value) in old where defaults.object(forKey: key) == nil { defaults.set(value, forKey: key) }
        let account = defaults.string(forKey: "username") ?? "admin"
        if Keychain.read(account) == nil, let password = Keychain.read(account, service: oldId) {
            Keychain.save(password, account: account)
        }
    }
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
    /// Обрывы связи, от новых к старым: из прочитанного журнала плюс история, которую ведёт routerdiag (drops.json).
    @Published var drops: [ConnectionDrop] = []
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
    /// MAC устройств, у которых идёт запись блокировки интернета: кнопка неактивна до ответа роутера.
    @Published var blockingMacs: Set<String> = []
    /// Устройство, для которого открыто подтверждение блокировки.
    @Published var confirmBlockMac: String?
    /// Ошибка последней блокировки по MAC: показывается в карточке устройства, а не за ней.
    @Published var blockErrors: [String: String] = [:]
    /// Растёт при каждой записи блокировки: снимок опроса, начатый до неё, мог застать старый флаг и не применяется.
    private var blockSeq = 0

    private var client: RouterClient?
    private var pollTask: Task<Void, Never>?
    private var rebootNoticeUntil = Date.distantPast

    // Background refreshes must not wipe the reboot notice while the router is going down.
    private func setBanner(_ text: String?) {
        banner = bannerAfterPoll(text, current: banner, rebootNotice: Date() < rebootNoticeUntil, writeError: writeError)
    }

    private var writeError: String?   // ошибка записи держится, пока запись не пройдёт: опрос её не стирает

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
            known[d.mac] = KnownDevice(mac: d.mac, name: d.name, type: d.type, connection: d.connection, lastSeen: mergedLastSeen(online: d.online, previous: known[d.mac]?.lastSeen ?? 0, now: now))
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
        log = nil
        drops = []
        editorOpen = false
        resetBlocking()
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
        let seq = blockSeq
        do {
            let fresh = try await client.snapshot()
            // Ответ устарел: за время чтения вышли из сессии или записали блокировку (она сама перечитает снимок).
            guard self.client === client, blockSeq == seq else { return }
            snapshot = fresh
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
        do {
            let entries = try await client.systemLog(security: security)
            log = entries
            if !security { drops = connectionDrops(entries, line: (try? await client.lineStats()) ?? nil) }
            setBanner(nil)
        } catch { setBanner(error.localizedDescription) }
    }

    /// Историю обрывов пишет только routerdiag (каждые 20 минут); приложение её читает и дополняет свежим журналом.
    private func connectionDrops(_ entries: [LogEntry], line: LineStats?) -> [ConnectionDrop] {
        let url = URL(fileURLWithPath: NSHomeDirectory() + "/Library/Application Support/ZyxelRouter/diagnostics/drops.json")
        let stored = (try? JSONDecoder().decode(DropLog.self, from: Data(contentsOf: url))) ?? DropLog()
        let link = snapshot.map {
            LinkStatus(routerUptime: $0.uptime, wanUp: $0.internetUp, wanUptime: $0.internetUp ? $0.internetUptime : 0, pppoe: $0.encapsulation.hasPrefix("PPP"), line: line)
        }
        return stored.record(entries, link: link, now: Int(Date().timeIntervalSince1970)).drops.reversed()
    }

    /// Запись с восстановлением сессии: опрос (только чтение) об устаревшем CSRF-токене не узнает.
    private func recovering<T>(_ c: RouterClient, retry: Bool = true, _ block: () async throws -> T) async throws -> T {
        let (user, pass) = (username, password)
        let result = try await withSessionRecovery(retry: retry, relogin: { [weak self] in
            // После выхода (или смены клиента) не входим заново: иначе на роутере осталась бы чужая для приложения сессия.
            guard !pass.isEmpty, self?.client === c else { throw RouterError.sessionExpired }
            try await c.login(username: user, password: pass)
        }, block)
        writeError = nil
        return result
    }

    /// Отказ в повторном входе нельзя глушить баннером: дальнейшие попытки могут заблокировать вход на роутере.
    private func sessionLost(_ error: Error) -> Bool {
        switch error {
        case RouterError.invalidCredentials, RouterError.locked: handleLoginFailure(error); return true
        default: return false
        }
    }

    /// Ошибка записи: показываем её и молча перечитываем состояние (loadParental стёр бы баннер).
    private func writeFailed(_ error: Error, _ c: RouterClient) async {
        if sessionLost(error) { return }
        writeError = error.localizedDescription
        banner = writeError
        if let fresh = try? await c.parentalControl() { parental = fresh }
    }

    func setParentalEnabled(_ on: Bool) async {
        guard let client else { return }
        do {
            parental = try await recovering(client) {
                try await client.setParentalEnabled(on)
                return try await client.parentalControl()
            }
            banner = nil
        } catch { await writeFailed(error, client) }
    }

    func setProfileEnabled(index: Int, on: Bool) async {
        guard let client else { return }
        do {
            parental = try await recovering(client) {
                try await client.setProfileEnabled(index: index, on: on)
                return try await client.parentalControl()
            }
            banner = nil
        } catch { await writeFailed(error, client) }
    }

    /// Блокирует или разрешает устройству интернет, затем перечитывает снимок, чтобы показать принятое роутером.
    /// Запись и чтение разделены: сбой чтения после принятой записи — не отказ, и запись из-за него не повторяется.
    func setInternetBlocked(mac: String, blocked: Bool) async {
        // По одной записи за раз: клиент — actor, и две записи могли бы уйти с одним CSRF-токеном (роутер вращает его).
        guard let client, !mac.isEmpty, blockingMacs.isEmpty else { return }
        blockingMacs.insert(mac)
        blockErrors[mac] = nil
        blockSeq += 1
        defer { if self.client === client { blockingMacs.remove(mac) } }
        do {
            // Флаг ставится, а не переключается, поэтому запись можно повторить после нового входа.
            try await recovering(client) { try await client.setInternetBlocked(mac: mac, blocked: blocked) }
        } catch {
            guard self.client === client else { return }   // вышли из сессии, пока шла запись
            if sessionLost(error) { return }
            blockErrors[mac] = error.localizedDescription
            return
        }
        blockSeq += 1
        guard let fresh = try? await client.snapshot(), self.client === client else { return }   // опрос покажет позже
        snapshot = fresh
        updated = Date()
    }

    private func resetBlocking() {
        blockingMacs = []
        blockErrors = [:]
        confirmBlockMac = nil
        blockSeq += 1
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
            // Создание не повторяем автоматически: при 504 профиль мог уже создаться, и повтор сделал бы дубль.
            let (index, macs, enabled) = (editorIndex, Array(editMacs), editEnabled)
            try await recovering(client, retry: index != nil) {
                try await client.saveProfile(index: index, name: name, macs: macs, days: days, timeRangeList: timeRangeList, enabled: enabled)
            }
        } catch {
            if sessionLost(error) { return }
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
            parental = try await recovering(client) {
                try await client.deleteProfile(index: index)
                return try await client.parentalControl()
            }
            banner = nil
        } catch { await writeFailed(error, client) }
    }

    func reboot() async {
        guard let client else { return }
        do {
            try await recovering(client, retry: false) { try await client.reboot() }
            banner = "Роутер перезагружается. Интернет вернётся через несколько минут."
            rebootNoticeUntil = Date().addingTimeInterval(180)
        } catch {
            if sessionLost(error) { return }
            writeError = error.localizedDescription
            banner = writeError
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
        log = nil
        drops = []
        editorOpen = false
        resetBlocking()
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
