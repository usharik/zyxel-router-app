import Foundation
import RouterKit

// Ежедневная глубокая диагностика роутера и связи. Только чтение: ничего на роутере не меняет.
// Запуск: ROUTER_PASSWORD=... routerdiag [ip] [--no-speedtest] [--no-notify] [--open]
//         ROUTER_PASSWORD=... routerdiag [ip] --drops-only [--no-notify]
// --drops-only — частый лёгкий режим (каждые 20 минут из launchd): только журнал и состояние линии, чтобы обрывы
// связи попали в историю раньше, чем уйдут из короткого журнала роутера. Отчёт не пишется, уведомление — только
// о новых обрывах.
// Обычно запускается из launchd через routerdiag.sh (пароль берётся из Связки ключей приложения).
// Отчёты: ~/Library/Application Support/ZyxelRouter/diagnostics/ (DIAG_DIR переопределяет).

signal(SIGPIPE, SIG_IGN)   // запись в stdin завершившегося curl не должна ронять процесс
let env = ProcessInfo.processInfo.environment
let args = CommandLine.arguments.dropFirst()
// Настройки приложения; до 1.4 оно называлось com.usharik.zyxelrouter.
let appDefaults = ["com.usharik.routermanager", "com.usharik.zyxelrouter"].compactMap { UserDefaults(suiteName: $0) }
func appSetting(_ key: String) -> String? { appDefaults.lazy.compactMap { $0.string(forKey: key) }.first }
let address = args.first(where: { !$0.hasPrefix("--") }) ?? appSetting("address") ?? "192.168.1.1"
let user = env["ROUTER_USER"] ?? appSetting("username") ?? "admin"
let password = env["ROUTER_PASSWORD"] ?? ""
let speedtest = !args.contains("--no-speedtest") && env["DIAG_SPEEDTEST"] != "0"
let notify = !args.contains("--no-notify")
let dropsOnly = args.contains("--drops-only")
let routerHost = String(address.split(separator: ":").first ?? "")

let baseDir = URL(fileURLWithPath: env["DIAG_DIR"] ?? (NSHomeDirectory() + "/Library/Application Support/ZyxelRouter/diagnostics"))
let historyDir = baseDir.appendingPathComponent("history")
let reportsDir = baseDir.appendingPathComponent("reports")
for d in [historyDir, reportsDir] { try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true) }

func log(_ s: String) { FileHandle.standardError.write(Data("[\(Date())] \(s)\n".utf8)) }

/// Запускает утилиту с ограничением по времени и возвращает stdout. Не полагается на Process.isRunning:
/// ждёт закрытия stdout (выход процесса) на фоновой очереди, по таймауту завершает процесс.
func run(_ path: String, _ arguments: [String], timeout: TimeInterval = 60, stdin: Data? = nil) -> String {
    let p = Process()
    p.executableURL = URL(fileURLWithPath: path)
    p.arguments = arguments
    let out = Pipe(), input = Pipe()
    p.standardOutput = out
    p.standardError = FileHandle.nullDevice
    p.standardInput = stdin == nil ? FileHandle.nullDevice : input
    do { try p.run() } catch { return "" }
    if let stdin {
        DispatchQueue.global().async {
            try? input.fileHandleForWriting.write(contentsOf: stdin)   // при раннем выходе curl запись просто оборвётся
            try? input.fileHandleForWriting.close()
        }
    }
    let done = DispatchSemaphore(value: 0)
    final class Box: @unchecked Sendable { var data = Data() }
    let box = Box()
    DispatchQueue.global().async {
        box.data = out.fileHandleForReading.readDataToEndOfFile()
        done.signal()
    }
    if done.wait(timeout: .now() + timeout) == .timedOut {
        p.terminate()
        _ = done.wait(timeout: .now() + 5)
    }
    return String(data: box.data, encoding: .utf8) ?? ""
}

// MARK: - Проверки с компьютера

func localProbe() -> LocalProbe {
    var probe = LocalProbe()
    let route = run("/sbin/route", ["-n", "get", "default"], timeout: 5)
    probe.interface = route.firstMatch(of: #/interface: (\S+)/#).map { String($0.1) } ?? ""
    probe.gateway = route.firstMatch(of: #/gateway: (\S+)/#).map { String($0.1) } ?? ""
    // Имя порта (Wi-Fi / Ethernet / Thunderbolt Ethernet …) по устройству enN.
    let ports = run("/usr/sbin/networksetup", ["-listallhardwareports"], timeout: 10)
    for block in ports.components(separatedBy: "\n\n") where block.contains("Device: \(probe.interface)\n") || block.hasSuffix("Device: \(probe.interface)") {
        let name = block.firstMatch(of: #/Hardware Port: (.+)/#).map { String($0.1) } ?? ""
        probe.interfaceKind = name.contains("Wi-Fi") ? "Wi-Fi" : (name.isEmpty ? "" : "Ethernet (\(name))")
    }
    log("сеть: \(probe.interface) \(probe.interfaceKind), шлюз \(probe.gateway)")

    probe.pingRouter = parsePing(run("/sbin/ping", ["-q", "-c", "30", "-i", "0.2", "-t", "15", routerHost], timeout: 20))
    for host in ["1.1.1.1", "8.8.8.8"] {
        probe.pingInternet[host] = parsePing(run("/sbin/ping", ["-q", "-c", "30", "-i", "0.2", "-t", "15", host], timeout: 20))
    }
    let dig = ["+tries=1", "+time=3", "+noall", "+comments", "+stats"]
    probe.dnsRouterCached = parseDig(run("/usr/bin/dig", dig + ["@\(routerHost)", "www.google.com"], timeout: 8))
    // Случайное имя не может быть в кэше — меряем полный путь до DNS провайдера.
    let random = "diag-\(UUID().uuidString.prefix(8).lowercased()).example.com"
    probe.dnsRouterUncached = parseDig(run("/usr/bin/dig", dig + ["@\(routerHost)", random], timeout: 8))
    probe.dnsPublic = parseDig(run("/usr/bin/dig", dig + ["@1.1.1.1", random], timeout: 8))

    let http = run("/usr/bin/curl", ["-s", "-o", "/dev/null", "--max-time", "10", "-w", "%{time_connect} %{time_starttransfer} %{http_code}",
                                     "https://www.google.com/generate_204"], timeout: 15).split(separator: " ")
    if http.count == 3, http[2] != "000", let c = Double(http[0]), let f = Double(http[1]) {
        probe.httpsConnectMs = Int(c * 1000); probe.httpsFirstByteMs = Int(f * 1000)
    }
    if speedtest {
        // Cloudflare отдаёт не больше 25 МБ за запрос; три замера, берём лучший.
        let best = (0..<3).compactMap { _ -> Double? in
            let out = run("/usr/bin/curl", ["-s", "-o", "/dev/null", "--max-time", "15", "-w", "%{http_code} %{speed_download}",
                                            "https://speed.cloudflare.com/__down?bytes=25000000"], timeout: 20).split(separator: " ")
            return out.count == 2 && out[0] == "200" ? Double(out[1]) : nil
        }.max()
        if let bps = best, bps > 0 { probe.downloadMbps = bps * 8 / 1_000_000 }
        let up = run("/usr/bin/curl", ["-s", "-o", "/dev/null", "--max-time", "15", "-w", "%{http_code} %{speed_upload}", "--data-binary", "@-",
                                       "https://speed.cloudflare.com/__up"], timeout: 20, stdin: Data(count: 25_000_000)).split(separator: " ")
        if up.count == 2, up[0] == "200", let bps = Double(up[1]), bps > 0 { probe.uploadMbps = bps * 8 / 1_000_000 }
    }
    return probe
}

// MARK: - Данные роутера

func objects(_ client: RouterClient, _ oid: String) async -> [[String: Any]] {
    do { return ((try await client.rawDAL(oid))["Object"] as? [Any])?.compactMap { $0 as? [String: Any] } ?? [] }
    catch { log("\(oid): \(error.localizedDescription)"); return [] }
}

func fetchRouter() async -> (RouterDiagInput?, String?) {
    guard !password.isEmpty else { return (nil, "пароль не найден (ROUTER_PASSWORD пуст — сохраните пароль в приложении с «Запомнить»)") }
    let client: RouterClient
    do { client = try RouterClient(address: address) } catch { return (nil, error.localizedDescription) }
    // Роутер допускает одну сессию: если открыто приложение или веб-интерфейс, ждём и пробуем снова.
    var lastError = ""
    for attempt in 1...4 {
        do { try await client.login(username: user, password: password); lastError = ""; break }
        catch {
            lastError = error.localizedDescription
            log("вход, попытка \(attempt): \(lastError)")
            if case RouterError.invalidCredentials = error { return (nil, lastError) }
            if case RouterError.locked = error { return (nil, lastError) }
            if attempt < 4 { try? await Task.sleep(nanoseconds: 90_000_000_000) }
        }
    }
    if !lastError.isEmpty { return (nil, lastError) }
    let status = await objects(client, "cardpage_status").first ?? [:]
    let hosts = await objects(client, "lanhosts").first ?? [:]
    let traffic = await objects(client, "Traffic_Status").first ?? [:]
    let mgmt = await objects(client, "mgmt_srv")
    let firewall = await objects(client, "firewall").first ?? [:]
    let time = await objects(client, "time").first ?? [:]
    let parental = try? await client.parentalControl()
    let syslog = (try? await client.systemLog()) ?? []
    let seclog = (try? await client.systemLog(security: true)) ?? []
    let line = (try? await client.lineStats()) ?? nil
    await client.logout()
    if status.isEmpty { return (nil, "роутер не вернул cardpage_status") }
    return (RouterDiagInput(status: status, hosts: hosts, traffic: traffic, mgmt: mgmt, firewall: firewall, time: time,
                            parental: parental, systemLog: syslog, securityLog: seclog, line: line), nil)
}

// MARK: - История

let decoder = JSONDecoder()
let encoder = JSONEncoder()
encoder.outputFormatting = [.prettyPrinted, .sortedKeys]

func loadHistory() -> [DiagMetrics] {
    let files = (try? FileManager.default.contentsOfDirectory(at: historyDir, includingPropertiesForKeys: nil)) ?? []
    return files.filter { $0.pathExtension == "json" }.sorted { $0.lastPathComponent < $1.lastPathComponent }.suffix(30)
        .compactMap { try? decoder.decode(DiagMetrics.self, from: Data(contentsOf: $0)) }
}

let knownURL = baseDir.appendingPathComponent("known_devices.json")
let known = (try? decoder.decode([String: KnownDevice].self, from: Data(contentsOf: knownURL))) ?? [:]

func prune(_ dir: URL, keep: Int) {
    let files = ((try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []).sorted { $0.lastPathComponent < $1.lastPathComponent }
    for f in files.dropLast(keep) { try? FileManager.default.removeItem(at: f) }
}

// MARK: - Обрывы связи

// История обрывов (общая для полного и частого режима) и журнал обрывов для чтения человеком.
let dropsURL = baseDir.appendingPathComponent("drops.json")
let dropsLogURL = baseDir.appendingPathComponent("drops.log")
// После отказа «неверный пароль» частый режим сутки не входит: 72 неудачных входа в день заблокировали бы учётную запись.
let authFailedURL = baseDir.appendingPathComponent("drops.auth_failed")

func loadDrops() -> DropLog { (try? decoder.decode(DropLog.self, from: Data(contentsOf: dropsURL))) ?? DropLog() }

/// Сохраняет историю; каждый новый обрыв дописывается в drops.log отдельным блоком со всем, что о нём известно.
func saveDrops(_ report: DiagReport) {
    do { try encoder.encode(report.drops).write(to: dropsURL, options: .atomic) } catch { log("drops.json: \(error.localizedDescription)") }
    guard !report.newDrops.isEmpty else { return }
    var text = ""
    for d in report.newDrops {
        text += "=== ОБРЫВ СВЯЗИ \(d.whenText()) ===\n"
        for (k, v) in d.details() { text += "\(k): \(v)\n" }
        if !d.lines.isEmpty { text += "Журнал роутера:\n" + d.lines.map { "  " + $0 }.joined(separator: "\n") + "\n" }
        text += "\n"
    }
    if let handle = try? FileHandle(forWritingTo: dropsLogURL) {
        defer { try? handle.close() }
        _ = try? handle.seekToEnd()
        try? handle.write(contentsOf: Data(text.utf8))
    } else {
        try? Data(text.utf8).write(to: dropsLogURL)
    }
}

func esc(_ s: String) -> String { s.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"") }
func showNotification(title: String, text: String, subtitle: String) {
    _ = run("/usr/bin/osascript", ["-e", "display notification \"\(esc(text))\" with title \"\(esc(title))\" subtitle \"\(esc(subtitle))\""], timeout: 10)
}

if dropsOnly {
    // Роутер допускает одну сессию. Если она занята (открыто приложение или веб-интерфейс) или роутер недоступен,
    // запуск молча пропускается: следующий — через 20 минут, а журнал роутера хранит несколько часов.
    guard !password.isEmpty else { log("обрывы: пароль не найден — пропуск"); exit(0) }
    if let stamp = (try? FileManager.default.attributesOfItem(atPath: authFailedURL.path))?[.modificationDate] as? Date, Date().timeIntervalSince(stamp) < 86400 {
        log("обрывы: роутер недавно отверг пароль — пропуск до полной диагностики"); exit(0)
    }
    guard (parsePing(run("/sbin/ping", ["-q", "-c", "2", "-t", "3", routerHost], timeout: 6))?.received ?? 0) > 0 else {
        log("обрывы: роутер \(routerHost) недоступен — пропуск"); exit(0)
    }
    let client = try RouterClient(address: address)
    do { try await client.login(username: user, password: password) }
    catch {
        if case RouterError.invalidCredentials = error { FileManager.default.createFile(atPath: authFailedURL.path, contents: nil) }
        log("обрывы: вход не удался (\(error.localizedDescription)) — пропуск"); exit(0)
    }
    try? FileManager.default.removeItem(at: authFailedURL)
    let status = await objects(client, "cardpage_status").first ?? [:]
    let syslog = try? await client.systemLog()
    let line = (try? await client.lineStats()) ?? nil
    await client.logout()
    guard let syslog, !status.isEmpty else { log("обрывы: роутер не отдал журнал или состояние — пропуск"); exit(0) }
    let snap = parseSnapshot(status: status, hosts: [:])
    var report = DiagReport(date: Date())
    report.drops = loadDrops()
    evaluateDrops(&report, log: syslog,
                  link: LinkStatus(routerUptime: snap.uptime, wanUp: snap.internetUp, wanUptime: snap.internetUp ? snap.internetUptime : 0,
                                   pppoe: snap.encapsulation.hasPrefix("PPP"), line: line), now: report.date)
    saveDrops(report)
    log("обрывы: записей журнала \(syslog.count), новых обрывов \(report.newDrops.count), в истории \(report.drops.drops.count)")
    for d in report.newDrops { print("\(d.whenText()) | \(d.durationText) | \(d.kindText) | причина: \(d.causeLabel) | линия: \(d.lineText)") }
    if notify, let last = report.newDrops.last {
        showNotification(title: "🟡 Роутер: обрыв связи", text: "\(last.whenWithDurationText()) — \(last.kindText)" + (report.newDrops.count > 1 ? " (+ещё \(report.newDrops.count - 1))" : ""),
                         subtitle: "Подробности: drops.log")
    }
    exit(0)
}

// MARK: - Запуск

log("диагностика \(address) начата")
var probe = localProbe()
var (input, routerError) = (RouterDiagInput?.none, String?.none)
if !probe.gateway.isEmpty, probe.gateway != routerHost, (probe.pingRouter?.received ?? 0) == 0 {
    routerError = "компьютер не в домашней сети (шлюз \(probe.gateway)), роутер \(routerHost) недоступен"
    // Вне дома результаты проверок связи не про этот роутер — в историю их не пишем.
    probe.pingInternet = [:]
} else {
    (input, routerError) = await fetchRouter()
}

let now = Date()
let report = diagnose(router: input, routerError: routerError, local: probe, history: loadHistory(), knownDevices: known,
                      routerAddress: routerHost, now: now, drops: loadDrops())
let stamp: String = { let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd_HHmm"; return f.string(from: now) }()
let markdown = renderMarkdown(report)
let reportURL = reportsDir.appendingPathComponent("\(stamp).md")
try markdown.write(to: reportURL, atomically: true, encoding: .utf8)
try markdown.write(to: baseDir.appendingPathComponent("latest.md"), atomically: true, encoding: .utf8)
if report.metrics.routerReachable || probe.gateway == routerHost {
    try encoder.encode(report.metrics).write(to: historyDir.appendingPathComponent("\(stamp).json"))
}
if report.metrics.routerReachable {
    try encoder.encode(report.knownDevices).write(to: knownURL)
    saveDrops(report)
    try? FileManager.default.removeItem(at: authFailedURL)
}
prune(reportsDir, keep: 120)
prune(historyDir, keep: 120)
print(markdown)
log("готово: \(reportURL.path)")

if notify {
    showNotification(title: "\(report.level.mark) Роутер: утренняя диагностика", text: notificationSummary(report), subtitle: "Отчёт: latest.md")
}
if args.contains("--open") || (env["DIAG_OPEN_ON_PROBLEM"] == "1" && report.level >= .warn) {
    _ = run("/usr/bin/open", [reportURL.path], timeout: 10)
}
