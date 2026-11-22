import Foundation

// Глубокая диагностика: чистые функции оценки (тестируются на фикстурах), ввод-вывод — в цели routerdiag.

public enum DiagLevel: Int, Codable, Comparable, Sendable {
    case ok, info, warn, crit
    public static func < (a: DiagLevel, b: DiagLevel) -> Bool { a.rawValue < b.rawValue }
    public var mark: String { ["🟢", "🔵", "🟡", "🔴"][rawValue] }
}

public struct DiagFinding: Codable, Sendable {
    public let level: DiagLevel
    public let area: String
    public let text: String
    public init(_ level: DiagLevel, _ area: String, _ text: String) { self.level = level; self.area = area; self.text = text }
}

public struct PingStats: Codable, Sendable, Equatable {
    public let sent: Int
    public let received: Int
    public let avgMs: Double
    public let maxMs: Double
    public let jitterMs: Double
    public var lossPercent: Double { sent == 0 ? 100 : Double(sent - received) * 100 / Double(sent) }
}

public struct DnsResult: Codable, Sendable, Equatable {
    public let status: String   // NOERROR / NXDOMAIN / SERVFAIL / TIMEOUT
    public let ms: Int
    public var ok: Bool { status == "NOERROR" || status == "NXDOMAIN" }
}

/// Проверки с компьютера: связь до роутера и в интернет, DNS, HTTPS, скорость.
public struct LocalProbe: Codable, Sendable {
    public var interface = ""            // en0 …
    public var interfaceKind = ""        // Wi-Fi / Ethernet
    public var gateway = ""
    public var pingRouter: PingStats?
    public var pingInternet: [String: PingStats] = [:]
    public var dnsRouterCached: DnsResult?
    public var dnsRouterUncached: DnsResult?
    public var dnsPublic: DnsResult?
    public var httpsConnectMs: Int?
    public var httpsFirstByteMs: Int?
    public var downloadMbps: Double?
    public var uploadMbps: Double?
    public init() {}
}

/// Сохраняемые между запусками показатели — для сравнения с прошлым днём и медианой за неделю.
public struct DiagMetrics: Codable, Sendable {
    public var date: Double = 0
    public var routerReachable = false
    public var firmware = ""
    public var uptime = 0
    public var internetUp = false
    public var internetUptime = 0
    public var dslDown = 0
    public var dslUp = 0
    public var wanRx = 0, wanTx = 0, wanErrors = 0, wanDiscards = 0, wanPackets = 0
    public var lanErrors = 0, lanDiscards = 0
    public var devicesOnline = 0
    public var devicesTotal = 0
    public var attacks24h = 0
    public var logErrors24h = 0
    public var clockDriftSec: Int?
    public var local = LocalProbe()
    public init() {}
}

/// Сырые ответы роутера, нужные для оценки.
public struct RouterDiagInput {
    public var status: [String: Any]       // cardpage_status Object[0]
    public var hosts: [String: Any]        // lanhosts Object[0]
    public var traffic: [String: Any]      // Traffic_Status Object[0]
    public var mgmt: [[String: Any]]       // mgmt_srv
    public var firewall: [String: Any]     // firewall Object[0]
    public var time: [String: Any]         // time Object[0]
    public var parental: ParentalControl?
    public var systemLog: [LogEntry]
    public var securityLog: [LogEntry]
    public init(status: [String: Any], hosts: [String: Any], traffic: [String: Any], mgmt: [[String: Any]], firewall: [String: Any],
                time: [String: Any], parental: ParentalControl?, systemLog: [LogEntry], securityLog: [LogEntry]) {
        self.status = status; self.hosts = hosts; self.traffic = traffic; self.mgmt = mgmt; self.firewall = firewall
        self.time = time; self.parental = parental; self.systemLog = systemLog; self.securityLog = securityLog
    }
}

public struct KnownDevice: Codable, Sendable {
    public var name: String
    public var firstSeen: Double
    public var lastSeen: Double
}

public struct DiagReport {
    public var date: Date
    public var findings: [DiagFinding] = []
    public var sections: [(title: String, rows: [(String, String)])] = []
    public var metrics = DiagMetrics()
    public var knownDevices: [String: KnownDevice] = [:]
    public var level: DiagLevel { findings.map(\.level).max() ?? .ok }
    public init(date: Date) { self.date = date }
}

// MARK: - Разбор вывода системных утилит

/// Разбирает вывод `ping -q` macOS.
public func parsePing(_ output: String) -> PingStats? {
    guard let m = output.firstMatch(of: #/(\d+) packets transmitted, (\d+) (?:packets )?received/#),
          let sent = Int(m.1), let received = Int(m.2) else { return nil }
    var avg = 0.0, max = 0.0, dev = 0.0
    if let r = output.firstMatch(of: #/= ([\d.]+)\/([\d.]+)\/([\d.]+)\/([\d.]+) ms/#) {
        avg = Double(r.2) ?? 0; max = Double(r.3) ?? 0; dev = Double(r.4) ?? 0
    }
    return PingStats(sent: sent, received: received, avgMs: avg, maxMs: max, jitterMs: dev)
}

/// Разбирает вывод `dig`: статус ответа и время запроса.
public func parseDig(_ output: String) -> DnsResult {
    let status = output.firstMatch(of: #/status: ([A-Z]+)/#).map { String($0.1) } ?? "TIMEOUT"
    let ms = output.firstMatch(of: #/Query time: (\d+) msec/#).flatMap { Int($0.1) } ?? 0
    return DnsResult(status: status, ms: ms)
}

// MARK: - Вспомогательное

/// Дата записи журнала: года в журнале нет, берём текущий; «будущие» даты относим к прошлому году.
public func logDate(_ e: LogEntry, now: Date, calendar: Calendar = .current) -> Date? {
    guard e.month >= 1, e.day >= 1 else { return nil }
    let t = e.time.split(separator: ":").compactMap { Int($0) }
    var c = calendar.dateComponents([.year], from: now)
    c.month = e.month; c.day = e.day
    c.hour = t.count > 0 ? t[0] : 0; c.minute = t.count > 1 ? t[1] : 0; c.second = t.count > 2 ? t[2] : 0
    guard var d = calendar.date(from: c) else { return nil }
    if d > now.addingTimeInterval(86400) { d = calendar.date(byAdding: .year, value: -1, to: d) ?? d }
    return d
}

public func formatDuration(_ seconds: Int) -> String {
    let s = max(0, seconds)
    let d = s / 86400, h = s % 86400 / 3600, m = s % 3600 / 60
    if d > 0 { return "\(d) д \(h) ч" }
    if h > 0 { return "\(h) ч \(m) мин" }
    return "\(m) мин"
}

func median(_ values: [Int]) -> Int? {
    let v = values.sorted()
    return v.isEmpty ? nil : v[v.count / 2]
}

private func fmt(_ v: Double, _ digits: Int = 1) -> String {
    String(format: "%.\(digits)f", v).replacingOccurrences(of: ".", with: ",")
}

/// Тип атаки из строки журнала безопасности: «IPv6 SYN_FLOODING ATTACK:IN=…» → «IPv6 SYN_FLOODING».
func attackType(_ message: String) -> String {
    let head = message.components(separatedBy: ":").first ?? message
    return head.replacingOccurrences(of: " ATTACK", with: "").trimmingCharacters(in: .whitespaces)
}

// MARK: - Оценка

/// Собирает отчёт. `router == nil` — роутер недоступен или вход не удался (`routerError` поясняет).
/// `history` — прошлые запуски, от старых к новым.
public func diagnose(router: RouterDiagInput?, routerError: String?, local: LocalProbe, history: [DiagMetrics],
                     knownDevices: [String: KnownDevice], routerAddress: String, now: Date) -> DiagReport {
    var r = DiagReport(date: now)
    r.metrics.date = now.timeIntervalSince1970
    r.metrics.local = local
    r.knownDevices = knownDevices
    let previous = history.last(where: { $0.routerReachable })
    let elapsed = previous.map { Int(now.timeIntervalSince1970 - $0.date) }

    evaluateLocal(&r, local: local, routerAddress: routerAddress)
    guard let router else {
        r.findings.append(DiagFinding(.crit, "Роутер", "Данные роутера не получены: \(routerError ?? "неизвестная ошибка")."))
        return r
    }
    r.metrics.routerReachable = true
    evaluateRouter(&r, router, previous: previous, elapsed: elapsed, history: history, now: now)
    return r
}

private func evaluateLocal(_ r: inout DiagReport, local: LocalProbe, routerAddress: String) {
    var rows: [(String, String)] = []
    rows.append(("Подключение компьютера", [local.interfaceKind, local.interface].filter { !$0.isEmpty }.joined(separator: ", ")))
    if let p = local.pingRouter {
        rows.append(("Пинг до роутера", "\(fmt(p.avgMs)) мс (макс. \(fmt(p.maxMs)), джиттер \(fmt(p.jitterMs))), потери \(fmt(p.lossPercent))%"))
        if p.lossPercent >= 5 { r.findings.append(DiagFinding(.crit, "Локальная сеть", "Потери до роутера \(fmt(p.lossPercent))%.")) }
        else if p.lossPercent > 0 { r.findings.append(DiagFinding(.warn, "Локальная сеть", "Есть потери до роутера: \(fmt(p.lossPercent))%.")) }
        let limit = local.interfaceKind == "Wi-Fi" ? 30.0 : 5.0
        if p.received > 0, p.avgMs > limit {
            r.findings.append(DiagFinding(.warn, "Локальная сеть", "Высокая задержка до роутера: \(fmt(p.avgMs)) мс по \(local.interfaceKind.isEmpty ? "сети" : local.interfaceKind)."))
        }
    }
    for (host, p) in local.pingInternet.sorted(by: { $0.key < $1.key }) {
        rows.append(("Пинг \(host)", p.received == 0 ? "нет ответа" : "\(fmt(p.avgMs)) мс (макс. \(fmt(p.maxMs)), джиттер \(fmt(p.jitterMs))), потери \(fmt(p.lossPercent))%"))
    }
    let pings = Array(local.pingInternet.values)
    if !pings.isEmpty {
        let best = pings.min { $0.lossPercent < $1.lossPercent }!
        if best.received == 0 { r.findings.append(DiagFinding(.crit, "Интернет", "Нет ответа на пинг ни от одного внешнего узла.")) }
        else if best.lossPercent >= 3 { r.findings.append(DiagFinding(.warn, "Интернет", "Потери пакетов в интернет: \(fmt(best.lossPercent))%.")) }
        if best.received > 0, best.avgMs > 80 { r.findings.append(DiagFinding(.warn, "Интернет", "Высокая задержка в интернет: \(fmt(best.avgMs)) мс.")) }
        if best.received > 0, best.jitterMs > 20 { r.findings.append(DiagFinding(.info, "Интернет", "Заметный джиттер: \(fmt(best.jitterMs)) мс — возможны проблемы со звонками и играми.")) }
    }
    func dnsText(_ d: DnsResult?) -> String { d.map { $0.ok ? "\($0.ms) мс" : $0.status } ?? "—" }
    rows.append(("DNS роутера (кэш / без кэша)", "\(dnsText(local.dnsRouterCached)) / \(dnsText(local.dnsRouterUncached))"))
    rows.append(("DNS 1.1.1.1", dnsText(local.dnsPublic)))
    if let d = local.dnsRouterUncached ?? local.dnsRouterCached {
        if !d.ok {
            let publicOK = local.dnsPublic?.ok ?? false
            r.findings.append(DiagFinding(.crit, "DNS", "DNS-прокси роутера не отвечает (\(d.status))" + (publicOK ? ", а публичный DNS работает — проблема в роутере или DNS провайдера." : ".")))
        } else if d.ms > 300 {
            r.findings.append(DiagFinding(.warn, "DNS", "Медленное разрешение имён через роутер: \(d.ms) мс."))
        }
    }
    if let c = local.httpsConnectMs, let f = local.httpsFirstByteMs { rows.append(("HTTPS (соединение / первый байт)", "\(c) / \(f) мс")) }
    else { rows.append(("HTTPS", "не удалось")) }
    if let d = local.downloadMbps { rows.append(("Скорость загрузки", "\(fmt(d)) Мбит/с")) }
    if let u = local.uploadMbps { rows.append(("Скорость отдачи", "\(fmt(u)) Мбит/с")) }
    r.sections.append(("Проверки с компьютера", rows))
}

private func evaluateRouter(_ r: inout DiagReport, _ input: RouterDiagInput, previous: DiagMetrics?, elapsed: Int?, history: [DiagMetrics], now: Date) {
    let snap = parseSnapshot(status: input.status, hosts: input.hosts)
    var m = r.metrics
    m.firmware = snap.firmware
    m.uptime = snap.uptime
    m.internetUp = snap.internetUp
    m.internetUptime = snap.internetUptime
    // Перезагрузка: роутер работает меньше, чем прошло с прошлой проверки.
    let rebooted = elapsed.map { snap.uptime + 120 < $0 } ?? false

    // --- Система
    var sys: [(String, String)] = [("Модель", snap.model), ("Прошивка", snap.firmware), ("Работает без перезагрузки", formatDuration(snap.uptime))]
    if rebooted { r.findings.append(DiagFinding(.warn, "Система", "Роутер перезагружался с прошлой проверки (работает \(formatDuration(snap.uptime))).")) }
    else if snap.uptime < 86400 { r.findings.append(DiagFinding(.info, "Система", "Роутер работает всего \(formatDuration(snap.uptime)).")) }
    if let p = previous, !p.firmware.isEmpty, p.firmware != snap.firmware {
        r.findings.append(DiagFinding(.warn, "Система", "Прошивка изменилась: \(p.firmware) → \(snap.firmware) (обновление провайдером по TR-069?)."))
    }
    // Часы: от них зависит расписание родительского контроля.
    let date = Field.str(input.time["Date"]), time = Field.str(input.time["Time"])
    let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyy-MM-dd HH:mm:ss"
    if let routerNow = f.date(from: "\(date) \(time)") {
        let drift = Int(routerNow.timeIntervalSince(now))
        m.clockDriftSec = drift
        sys.append(("Часы роутера", "\(date) \(time) (расхождение \(drift) с)"))
        if abs(drift) > 300 {
            r.findings.append(DiagFinding(.warn, "Система", "Часы роутера расходятся с компьютером на \(formatDuration(abs(drift))) — проверьте NTP и часовой пояс; от этого зависит расписание родительского контроля."))
        }
    }
    r.sections.append(("Система", sys))

    // --- Интернет
    var net: [(String, String)] = [
        ("Интернет", snap.internetUp ? "подключён" : "НЕТ"), ("Тип", [snap.linkType, snap.encapsulation].filter { !$0.isEmpty }.joined(separator: " / ")),
        ("Сессия WAN", formatDuration(snap.internetUptime)),
    ]
    if !snap.internetUp { r.findings.append(DiagFinding(.crit, "Интернет", "Роутер сообщает, что WAN-подключение не установлено.")) }
    else {
        let sinceBootGap = snap.uptime - snap.internetUptime
        if let elapsed, !rebooted, snap.internetUptime + 60 < elapsed {
            r.findings.append(DiagFinding(.warn, "Интернет", "С прошлой проверки соединение с провайдером переподключалось (текущая сессия \(formatDuration(snap.internetUptime)))."))
        } else if !rebooted, sinceBootGap > 600, snap.internetUptime < 86400 {
            r.findings.append(DiagFinding(.info, "Интернет", "Сессия WAN моложе работы роутера на \(formatDuration(sinceBootGap)) — было переподключение."))
        }
    }
    let traffic = input.traffic
    let ip = Field.list(traffic["ipIface"]), ipSt = Field.list(traffic["ipIfaceSt"])
    let ppp = Field.list(traffic["pppIface"]), pppSt = Field.list(traffic["pppIfaceSt"])
    if let p = ppp.first(where: { Field.str($0["Status"]) == "Up" }) {
        let err = Field.str(p["LastConnectionError"])
        if !err.isEmpty, err != "ERROR_NONE" { r.findings.append(DiagFinding(.warn, "Интернет", "Последняя ошибка PPP: \(err).")) }
    }

    // DSL
    let channels = Field.list(input.status["DslChannelInfo"])
    if let dsl = channels.first(where: { Field.str($0["Status"]) == "Up" }) {
        m.dslDown = Field.int(dsl["DownstreamCurrRate"]); m.dslUp = Field.int(dsl["UpstreamCurrRate"])
        net.append(("Синхронизация DSL", "↓ \(fmt(Double(m.dslDown) / 1000)) / ↑ \(fmt(Double(m.dslUp) / 1000)) Мбит/с"))
        net.append(("Режим DSL", Field.str(dsl["LinkEncapsulationUsed"])))
        let delay = Field.int(dsl["ActualInterleavingDelay"])
        if delay > 0 { net.append(("Интерливинг", "\(delay) мс")) }
        if let base = median(history.suffix(7).map(\.dslDown).filter { $0 > 0 }), m.dslDown > 0, Double(m.dslDown) < Double(base) * 0.85 {
            r.findings.append(DiagFinding(.warn, "DSL", "Скорость синхронизации упала: \(fmt(Double(m.dslDown) / 1000)) Мбит/с против обычных \(fmt(Double(base) / 1000)) (медиана за неделю)."))
        }
        if let d = r.metrics.local.downloadMbps, m.dslDown > 0, d < Double(m.dslDown) / 1000 * 0.5 {
            let wifi = r.metrics.local.interfaceKind == "Wi-Fi" ? " Компьютер подключён по Wi-Fi — замер может упираться в беспроводную сеть." : ""
            r.findings.append(DiagFinding(.info, "Скорость", "Замер загрузки (\(fmt(d)) Мбит/с) меньше половины синхронизации DSL.\(wifi)"))
        }
    } else if ["ATM", "PTM"].contains(snap.linkType) || !channels.isEmpty && !snap.internetUp {
        r.findings.append(DiagFinding(.crit, "DSL", "Нет синхронизации DSL."))
    }

    // Счётчики WAN/LAN
    func counters(_ name: String) -> [String: Any]? {
        guard let i = ip.firstIndex(where: { Field.str($0["Name"]) == name && Field.str($0["Status"]) == "Up" }), i < ipSt.count else { return nil }
        // У PPPoE счётчики IP-интерфейса пустые — байты считает нижний PPP-интерфейс (PPP.Interface.N → pppIfaceSt[N-1]).
        let lower = Field.str(ip[i]["LowerLayers"])
        if lower.hasPrefix("PPP.Interface."), let n = Int(lower.dropFirst("PPP.Interface.".count)), n >= 1, n <= pppSt.count,
           Field.int(pppSt[n - 1]["PacketsReceived"]) > 0 { return pppSt[n - 1] }
        return ipSt[i]
    }
    let comparable = previous != nil && !rebooted
    if let w = counters("WAN") {
        m.wanRx = Field.int(w["BytesReceived"]); m.wanTx = Field.int(w["BytesSent"])
        m.wanErrors = Field.int(w["ErrorsReceived"]) + Field.int(w["ErrorsSent"])
        m.wanDiscards = Field.int(w["DiscardPacketsReceived"]) + Field.int(w["DiscardPacketsSent"])
        m.wanPackets = Field.int(w["PacketsReceived"]) + Field.int(w["PacketsSent"])
        if comparable, let p = previous, m.wanRx >= p.wanRx, m.wanTx >= p.wanTx, p.wanRx > 0 {
            net.append(("Трафик с прошлой проверки", "↓ \(formatBytes(m.wanRx - p.wanRx)) / ↑ \(formatBytes(m.wanTx - p.wanTx))"))
            let dPackets = m.wanPackets - p.wanPackets, dBad = (m.wanErrors - p.wanErrors) + (m.wanDiscards - p.wanDiscards)
            if dPackets > 10000, dBad >= 0, Double(dBad) / Double(dPackets) > 0.005 {
                r.findings.append(DiagFinding(.warn, "Интернет", "Ошибки/отброшенные пакеты на WAN: \(dBad) из \(dPackets) (\(fmt(Double(dBad) * 100 / Double(dPackets), 2))%)."))
            }
        } else {
            net.append(("Трафик с загрузки роутера", "↓ \(formatBytes(m.wanRx)) / ↑ \(formatBytes(m.wanTx))"))
        }
        net.append(("Ошибки / отброшено на WAN", "\(m.wanErrors) / \(m.wanDiscards)"))
    }
    if let l = counters("LAN") {
        m.lanErrors = Field.int(l["ErrorsReceived"]) + Field.int(l["ErrorsSent"])
        m.lanDiscards = Field.int(l["DiscardPacketsReceived"]) + Field.int(l["DiscardPacketsSent"])
    }
    r.sections.append(("Интернет", net))

    // --- Порты LAN
    var ports: [(String, String)] = []
    let eth = Field.list(traffic["ethIface"]), ethSt = Field.list(traffic["ethIfaceSt"])
    for (i, e) in eth.enumerated() where Field.str(e["Status"]) == "Up" {
        let name = Field.str(e["X_ZYXEL_LanPort"]).isEmpty ? Field.str(e["Name"]) : Field.str(e["X_ZYXEL_LanPort"])
        let rate = Field.int(e["CurrentBitRate"]), maxRate = Field.int(e["MaxBitRate"]), duplex = Field.str(e["DuplexMode"])
        let st = i < ethSt.count ? ethSt[i] : [:]
        let errs = Field.int(st["ErrorsReceived"]) + Field.int(st["ErrorsSent"])
        ports.append((name, "\(rate) Мбит/с, \(duplex == "Full" ? "полный дуплекс" : duplex), ошибок \(errs)"))
        if rate > 0, rate <= 10, maxRate >= 100 {
            r.findings.append(DiagFinding(.warn, "Порты LAN", "Порт \(name) согласовался на \(rate) Мбит/с (макс. \(maxRate)) — проверьте кабель или сетевую карту устройства."))
        } else if rate == 100, maxRate >= 1000 {
            r.findings.append(DiagFinding(.info, "Порты LAN", "Порт \(name) работает на 100 Мбит/с — нормально для ТВ/принтера, иначе проверьте кабель (нужны все 4 пары)."))
        }
        if duplex == "Half" { r.findings.append(DiagFinding(.warn, "Порты LAN", "Порт \(name) в полудуплексе — признак проблем с кабелем или согласованием.")) }
        if errs > 100 { r.findings.append(DiagFinding(.warn, "Порты LAN", "На порту \(name) \(errs) ошибок передачи.")) }
    }
    if ports.isEmpty { ports.append(("Активных портов", "нет")) }
    r.sections.append(("Порты LAN", ports))

    // --- Wi-Fi
    var wifi: [(String, String)] = []
    for radio in Field.list(traffic["wifiRadio"]) {
        let band = Field.str(radio["OperatingFrequencyBand"])
        let on = Field.bool(radio["Enable"]) && Field.str(radio["Status"]) == "Up"
        wifi.append(("Радио \(band)", on ? "канал \(Field.str(radio["Channel"]))\(Field.bool(radio["AutoChannelEnable"]) ? " (авто)" : ""), \(Field.str(radio["OperatingChannelBandwidth"])), мощность \(Field.int(radio["TransmitPower"]))%" : "выключено"))
        if Field.bool(radio["Enable"]), Field.str(radio["Status"]) != "Up" {
            r.findings.append(DiagFinding(.crit, "Wi-Fi", "Радио \(band) включено, но не работает (статус \(Field.str(radio["Status"])))."))
        }
    }
    for net in snap.wifi where net.enabled && (net.security.isEmpty || net.security == "None") {
        r.findings.append(DiagFinding(.warn, "Wi-Fi", "Сеть «\(net.ssid)» без пароля."))
    }
    let wifiClients = snap.devices.filter { $0.online && $0.connection.lowercased().contains("wi-fi") }
    let weak = wifiClients.filter { ($0.signal > 0 && $0.signal <= 1) || ($0.snr > 0 && $0.snr < 15) }
    for d in weak {
        r.findings.append(DiagFinding(.info, "Wi-Fi", "Слабый сигнал у «\(d.name)»: уровень \(d.signal)/5, SNR \(d.snr) дБ, \(d.phyRate) Мбит/с."))
    }
    let on24 = wifiClients.filter { $0.connection.contains("2.4") }
    wifi.append(("Клиентов Wi-Fi", "\(wifiClients.count) (2,4 ГГц: \(on24.count), 5 ГГц: \(wifiClients.count - on24.count))"))
    if !wifiClients.isEmpty {
        let avg = wifiClients.map(\.snr).reduce(0, +) / wifiClients.count
        wifi.append(("Средний SNR клиентов", "\(avg) дБ"))
    }
    r.sections.append(("Wi-Fi", wifi))

    // --- Устройства
    let online = snap.devices.filter(\.online)
    m.devicesOnline = online.count; m.devicesTotal = snap.devices.count
    var devRows: [(String, String)] = [("В сети / известно роутеру", "\(online.count) / \(snap.devices.count)")]
    let firstRun = r.knownDevices.isEmpty
    var newDevices: [RouterDevice] = []
    for d in snap.devices where !d.mac.isEmpty {
        if var k = r.knownDevices[d.mac] {
            if d.online { k.lastSeen = now.timeIntervalSince1970 }
            if !d.name.isEmpty, d.name != d.mac { k.name = d.name }
            r.knownDevices[d.mac] = k
        } else {
            r.knownDevices[d.mac] = KnownDevice(name: d.name, firstSeen: now.timeIntervalSince1970, lastSeen: now.timeIntervalSince1970)
            if !firstRun { newDevices.append(d) }
        }
    }
    if !newDevices.isEmpty {
        let list = newDevices.map { "«\($0.name)» (\($0.mac), \($0.connection.isEmpty ? "?" : $0.connection)\($0.guest ? ", гостевая" : ""))" }.joined(separator: ", ")
        r.findings.append(DiagFinding(.warn, "Устройства", "Новые устройства в сети: \(list)."))
    }
    let pool = Field.dict(input.status["Dhcp4SerPoolInfo"])
    if let lo = Field.str(pool["MinAddress"]).split(separator: ".").last.flatMap({ Int($0) }),
       let hi = Field.str(pool["MaxAddress"]).split(separator: ".").last.flatMap({ Int($0) }), hi >= lo {
        let size = hi - lo + 1
        let used = snap.devices.filter { !$0.staticIP && !$0.ip.isEmpty }.count
        devRows.append(("Пул DHCP", "\(used) из \(size) адресов"))
        if Double(used) > Double(size) * 0.8 { r.findings.append(DiagFinding(.warn, "Устройства", "Пул DHCP заполнен на \(used * 100 / size)%.")) }
    }
    let blocked = snap.devices.filter(\.blocked)
    if !blocked.isEmpty { devRows.append(("Заблокирован доступ в интернет", blocked.map(\.name).joined(separator: ", "))) }
    let top = online.sorted { $0.bytesDown + $0.bytesUp > $1.bytesDown + $1.bytesUp }.prefix(3)
    if !top.isEmpty { devRows.append(("Больше всего трафика", top.map { "\($0.name) (\(formatBytes($0.bytesDown + $0.bytesUp)))" }.joined(separator: ", "))) }
    r.sections.append(("Устройства", devRows))

    // --- Безопасность
    var sec: [(String, String)] = []
    let fwOn = Field.bool(input.firewall["Enable"])
    sec.append(("Межсетевой экран", fwOn ? "включён (уровень \(Field.str(input.firewall["Level_GUI"])))" : "ВЫКЛЮЧЕН"))
    if !input.firewall.isEmpty, !fwOn { r.findings.append(DiagFinding(.crit, "Безопасность", "Межсетевой экран выключен.")) }
    if !input.firewall.isEmpty, !Field.bool(input.firewall["enableDos"]) { r.findings.append(DiagFinding(.warn, "Безопасность", "Защита от DoS выключена.")) }
    var exposed: [String] = [], lanOnly: [String] = []
    for s in input.mgmt {
        let mode = Field.str(s["Mode"]).uppercased(), name = Field.str(s["Name"])
        if mode.contains("WAN") { exposed.append("\(name):\(Field.int(s["Port"]))") }
        else if mode.contains("LAN"), ["TELNET", "FTP", "SNMP"].contains(name.uppercased()) { lanOnly.append(name) }
    }
    sec.append(("Управление из интернета", exposed.isEmpty ? "закрыто" : exposed.joined(separator: ", ")))
    if !exposed.isEmpty { r.findings.append(DiagFinding(.crit, "Безопасность", "Управление роутером открыто из интернета: \(exposed.joined(separator: ", ")).")) }
    if !lanOnly.isEmpty { r.findings.append(DiagFinding(.info, "Безопасность", "В локальной сети включены небезопасные службы: \(lanOnly.joined(separator: ", ")).")) }

    let dayAgo = now.addingTimeInterval(-86400)
    let attacks = input.securityLog.filter { (logDate($0, now: now) ?? .distantPast) >= dayAgo }
    m.attacks24h = attacks.count
    let byType = logCountsBy(attacks) { attackType($0.message) }
    sec.append(("Отражено атак за сутки", attacks.isEmpty ? "0" : "\(attacks.count): " + byType.prefix(4).map { "\($0.label) — \($0.count)" }.joined(separator: ", ")))
    let sources = logCountsBy(attacks) { e in e.message.firstMatch(of: #/SRC=(\S+)/#).map { String($0.1) } ?? "" }
    if let s = sources.first, s.count >= 20 { sec.append(("Самый активный источник", "\(s.label) (\(s.count))")) }
    if let p = previous, p.attacks24h > 20, attacks.count > p.attacks24h * 5 {
        r.findings.append(DiagFinding(.info, "Безопасность", "Число отражённых атак выросло: \(attacks.count) против \(p.attacks24h) вчера (межсетевой экран их блокирует)."))
    }
    if input.securityLog.count >= 250 { sec.append(("Примечание", "журнал хранит ~300 последних записей, число может быть занижено")) }
    r.sections.append(("Безопасность", sec))

    // --- Журнал
    var logRows: [(String, String)] = []
    let recent = input.systemLog.filter { (logDate($0, now: now) ?? .distantPast) >= dayAgo }
    let severe: Set<String> = ["emerg", "alert", "crit", "err", "error"]
    let errors = recent.filter { severe.contains($0.severity.lowercased()) }
    m.logErrors24h = errors.count
    logRows.append(("Записей за сутки", "\(recent.count) (ошибок: \(errors.count))"))
    if !errors.isEmpty {
        logRows.append(("Ошибки по процессам", logCountsBy(errors) { $0.process }.prefix(5).map { "\($0.label) — \($0.count)" }.joined(separator: ", ")))
    }
    let critical = errors.filter { ["emerg", "alert", "crit"].contains($0.severity.lowercased()) }
    if !critical.isEmpty {
        r.findings.append(DiagFinding(.warn, "Журнал", "Критические записи в журнале за сутки: \(critical.count) (\(logCountsBy(critical) { $0.process }.prefix(3).map(\.label).joined(separator: ", ")))."))
    }
    let linkWords = ["link down", "link is down", "lcp terminated", "padt", "retrain", "showtime", "connection lost", "kernel panic", "oom"]
    let linkEvents = recent.filter { e in linkWords.contains { e.message.lowercased().contains($0) } }
    if !linkEvents.isEmpty {
        r.findings.append(DiagFinding(.warn, "Журнал", "События обрыва связи в журнале за сутки: \(linkEvents.count). Последнее: \(logDayLabel(linkEvents.last!)) \(linkEvents.last!.time) — \(linkEvents.last!.message.prefix(120))"))
    }
    let dhcp6 = errors.filter { $0.process.contains("dhcp6c") }
    if dhcp6.count >= 10 {
        r.findings.append(DiagFinding(.info, "Журнал", "Клиент DHCPv6 сообщает об ошибках (\(dhcp6.count) за сутки) — IPv6 от провайдера может работать нестабильно."))
    }
    if let first = input.systemLog.first, let d = logDate(first, now: now) {
        logRows.append(("Журнал охватывает", "с \(logDayLabel(first)) \(first.time) (\(formatDuration(Int(now.timeIntervalSince(d)))))"))
    }
    r.sections.append(("Системный журнал", logRows))

    // --- Родительский контроль
    if let pc = input.parental {
        let active = pc.profiles.filter(\.enabled)
        var rows: [(String, String)] = [("Включён", pc.enabled ? "да" : "нет"), ("Профилей (активных)", "\(pc.profiles.count) (\(active.count)) из \(pc.maxProfiles)")]
        let known = Set(r.knownDevices.keys.map { $0.replacingOccurrences(of: ":", with: "-") })
        let orphan = active.filter { p in !p.macs.isEmpty && p.macs.allSatisfy { !known.contains($0) } }
        if !orphan.isEmpty { rows.append(("Профили, чьи устройства не видны роутеру", orphan.map(\.name).joined(separator: ", "))) }
        r.sections.append(("Родительский контроль", rows))
    }

    r.metrics = m
}

// MARK: - Отчёт

public func renderMarkdown(_ r: DiagReport) -> String {
    let f = DateFormatter(); f.locale = Locale(identifier: "ru_RU"); f.dateFormat = "d MMMM yyyy, HH:mm"
    let verdict = ["Всё в порядке", "Всё в порядке, есть замечания", "Есть проблемы, требующие внимания", "Обнаружены серьёзные проблемы"][r.level.rawValue]
    var out = "# Диагностика роутера — \(f.string(from: r.date))\n\n**\(r.level.mark) \(verdict)**\n\n"
    let findings = r.findings.sorted { $0.level > $1.level }
    if findings.isEmpty { out += "Замечаний нет.\n\n" }
    else {
        out += "## Итоги\n\n"
        for x in findings { out += "- \(x.level.mark) **\(x.area).** \(x.text)\n" }
        out += "\n"
    }
    for s in r.sections where !s.rows.isEmpty {
        out += "## \(s.title)\n\n| Показатель | Значение |\n|---|---|\n"
        for (k, v) in s.rows { out += "| \(k) | \(v.replacingOccurrences(of: "|", with: "\\|")) |\n" }
        out += "\n"
    }
    return out
}

/// Короткий текст для уведомления.
public func notificationSummary(_ r: DiagReport) -> String {
    let top = r.findings.filter { $0.level >= .warn }.sorted { $0.level > $1.level }
    if top.isEmpty { return "Всё в порядке" + (r.findings.isEmpty ? "" : " (замечаний: \(r.findings.count))") }
    let first = top[0].text
    return (first.count > 110 ? String(first.prefix(107)) + "…" : first) + (top.count > 1 ? " (+ещё \(top.count - 1))" : "")
}
