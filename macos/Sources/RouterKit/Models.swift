import Foundation

public struct RouterBasic: Sendable {
    public let model: String
    public let firmware: String
    public let language: String
}

public struct RouterDevice: Identifiable, Sendable {
    public var id: String { mac.isEmpty ? name + ip : mac }
    public let name: String
    public let ip: String
    public let mac: String
    public let connection: String
    public let online: Bool
    public let guest: Bool
    /// Имя сети, в которой находится устройство: SSID для Wi-Fi, иначе проводная сеть.
    public let network: String
    // Расширенные сведения (многое в прошивке приходит пустым — тогда значения нулевые/пустые).
    public let type: String          // выведенный тип: «Телефон или планшет», «Принтер», …
    public let vendorClass: String   // VendorClassID (DHCP vendor class)
    public let signal: Int           // уровень Wi-Fi 0–5 (0 для проводных)
    public let snr: Int              // отношение сигнал/шум, дБ
    public let phyRate: Int          // скорость подключения Wi-Fi, Мбит/с
    public let wifiStandard: String  // g / n / ac
    public let bytesDown: Int        // принято байт
    public let bytesUp: Int          // отправлено байт
    public let ipv6: Bool            // есть адрес IPv6
    public let staticIP: Bool        // статический адрес, не DHCP
    public let leaseRemaining: Int   // остаток аренды DHCP, сек (может быть отрицательным)
    public let blocked: Bool         // заблокирован родительским контролем
    public let details: [String: String]  // все непустые поля устройства — для раздела «Все поля»
    public let lastSeen: Int         // когда устройство последний раз было в сети (epoch, сек); 0 — сейчас онлайн

    /// Запись об известном устройстве, которого сейчас нет в выдаче роутера (из локальной истории).
    public static func offline(mac: String, name: String, type: String, connection: String, lastSeen: Int) -> RouterDevice {
        RouterDevice(name: name.isEmpty ? mac : name, ip: "", mac: mac, connection: connection, online: false, guest: false,
                     network: connection.lowercased().contains("wi-fi") ? "Wi-Fi" : (connection.isEmpty ? "" : "Проводная сеть"),
                     type: type, vendorClass: "", signal: 0, snr: 0, phyRate: 0, wifiStandard: "", bytesDown: 0, bytesUp: 0,
                     ipv6: false, staticIP: false, leaseRemaining: 0, blocked: false, details: [:], lastSeen: lastSeen)
    }

    public var typeIcon: String {    // подсказка иконки для UI
        switch type {
        case "Телефон или планшет": return "iphone"
        case "Компьютер": return "desktopcomputer"
        case "Принтер": return "printer"
        case "ТВ-приставка": return "tv"
        case "IoT-устройство": return "sensor"
        default: return connection.lowercased().contains("wi-fi") ? "wifi" : "cable.connector.horizontal"
        }
    }
}

public struct WifiNetwork: Identifiable, Sendable {
    public var id: String { "\(band)-\(ssid)" }
    public let ssid: String
    public let band: String
    public let enabled: Bool
    public let channel: String
    public let security: String
    public let guest: Bool
}

// MARK: - Журнал роутера

public struct LogEntry: Identifiable, Sendable {
    public let id: Int
    public let category: String   // dhcpd
    public let severity: String   // info / err / warning …
    public let process: String
    public let message: String
    public let month: Int         // 1–12 (0 если не распознано)
    public let day: Int
    public let time: String
    public let raw: String
}

public enum LogPeriod: String, Sendable, CaseIterable { case day = "День", week = "Неделя", month = "Месяц" }

public struct LogCount: Identifiable, Sendable {
    public let label: String
    public let count: Int
    public var id: String { label }
}

private let ruMonths = ["янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек"]
private let engMonths = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"]

/// Разбирает журнал роутера. Вход — ответ GET_LOG: массив объектов с полем result (строки),
/// либо {Object:[…]}. Строка: "[category] Mon DD HH:MM:SS facility.severity process: message".
public func parseLog(_ raw: Any?) -> [LogEntry] {
    var lines: [String] = []
    func collect(_ v: Any?) {
        if let arr = v as? [Any] { for e in arr { collect(e) } }
        else if let d = v as? [String: Any] { collect(d["result"] ?? d["Object"]) }
        else if let s = v as? String { lines.append(s) }
    }
    collect(raw)
    return lines.enumerated().compactMap { index, line in
        let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        var category = "", rest = trimmed
        if trimmed.hasPrefix("["), let close = trimmed.firstIndex(of: "]") {
            category = String(trimmed[trimmed.index(after: trimmed.startIndex)..<close])
            rest = String(trimmed[trimmed.index(after: close)...]).trimmingCharacters(in: .whitespaces)
        }
        // Mon DD HH:MM:SS
        let parts = rest.split(separator: " ", omittingEmptySubsequences: true).map(String.init)
        var month = 0, day = 0, time = "", severity = "", process = "", message = rest
        if parts.count >= 4, let m = engMonths.firstIndex(of: parts[0].lowercased()) {
            month = m + 1
            day = Int(parts[1]) ?? 0
            time = parts[2]
            let facility = parts[3]                       // daemon.info
            severity = facility.contains(".") ? String(facility.split(separator: ".").last ?? "") : facility
            let tail = parts.dropFirst(4).joined(separator: " ")
            if let colon = tail.firstIndex(of: ":") {
                process = String(tail[..<colon])
                message = String(tail[tail.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
            } else { message = tail }
        }
        return LogEntry(id: index, category: category, severity: severity, process: process, message: message,
                        month: month, day: day, time: time, raw: trimmed)
    }
}

public func logDayLabel(_ e: LogEntry) -> String {
    guard e.month >= 1, e.month <= 12 else { return "—" }
    return "\(e.day) \(ruMonths[e.month - 1])"
}

/// Сводка по периодам: подписи и количества, по убыванию даты. Год берётся текущий (в журнале его нет).
public func logAggregate(_ entries: [LogEntry], by period: LogPeriod) -> [LogCount] {
    let year = Calendar.current.component(.year, from: Date())
    var cal = Calendar(identifier: .iso8601)
    cal.firstWeekday = 2
    struct Bucket { let order: Int; let label: String; var count: Int }
    var buckets: [String: Bucket] = [:]
    for e in entries where e.month >= 1 && e.month <= 12 {
        var comps = DateComponents(); comps.year = year; comps.month = e.month; comps.day = max(1, e.day)
        let date = cal.date(from: comps) ?? Date()
        let key: String, order: Int, label: String
        switch period {
        case .day: order = e.month * 100 + e.day; key = "d\(order)"; label = logDayLabel(e)
        case .week: let w = cal.component(.weekOfYear, from: date); order = w; key = "w\(w)"; label = "неделя \(w)"
        case .month: order = e.month; key = "m\(e.month)"; label = ruMonths[e.month - 1]
        }
        if var b = buckets[key] { b.count += 1; buckets[key] = b }
        else { buckets[key] = Bucket(order: order, label: label, count: 1) }
    }
    return buckets.values.sorted { $0.order > $1.order }.map { LogCount(label: $0.label, count: $0.count) }
}

public func logCountsBy(_ entries: [LogEntry], key: (LogEntry) -> String) -> [LogCount] {
    var counts: [String: Int] = [:]
    for e in entries { let k = key(e); if !k.isEmpty { counts[k, default: 0] += 1 } }
    return counts.sorted { $0.value > $1.value || ($0.value == $1.value && $0.key < $1.key) }.map { LogCount(label: $0.key, count: $0.value) }
}

public struct ParentalProfile: Identifiable, Sendable {
    public let index: Int
    public var id: Int { index }
    public let name: String
    public let enabled: Bool
    public let macs: [String]
    public let days: [String]
    public let times: [String]
    public let blocksSites: Bool
    public let blocksServices: Bool
}

public struct ParentalControl: Sendable {
    public let enabled: Bool
    public let maxProfiles: Int
    public let profiles: [ParentalProfile]
}

/// Одна строка маршрута для показа: сам роутер хранит четыре разных вида, здесь они сведены к заголовку и описанию.
public struct RouteItem: Identifiable, Sendable {
    public let id: Int
    public let title: String
    public let subtitle: String
    public let enabled: Bool
}

public struct Routing: Sendable {
    public let staticRoutes: [RouteItem]
    public let dnsRoutes: [RouteItem]
    public let policyRoutes: [RouteItem]
    public let rip: [RouteItem]
}

public struct RouterSnapshot: Sendable {
    public let model: String
    public let firmware: String
    public let uptime: Int
    public let internetUp: Bool
    public let linkType: String
    public let encapsulation: String
    public let wanIP: String
    public let dns: String
    public let internetUptime: Int
    public let downKbps: Int
    public let upKbps: Int
    public let devices: [RouterDevice]
    public let wifi: [WifiNetwork]
    public let schema: String
}

enum Field {
    static func dict(_ v: Any?) -> [String: Any] { v as? [String: Any] ?? [:] }
    // Поэлементно: один не-объект (например null) в массиве не должен обнулять весь список.
    static func list(_ v: Any?) -> [[String: Any]] { (v as? [Any])?.compactMap { $0 as? [String: Any] } ?? [] }

    static func str(_ v: Any?) -> String {
        let raw: String
        switch v {
        case let s as String: raw = s
        case let n as NSNumber: raw = n.stringValue
        default: return ""
        }
        return raw.replacingOccurrences(of: "&lt;", with: "<").replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"").replacingOccurrences(of: "&#39;", with: "'")
            .replacingOccurrences(of: "&amp;", with: "&")
    }

    static func int(_ v: Any?) -> Int {
        if let n = v as? NSNumber { return n.intValue }
        if let s = v as? String { return Int(s) ?? 0 }
        return 0
    }

    static func bool(_ v: Any?) -> Bool {
        if let n = v as? NSNumber { return n.intValue != 0 }
        if let s = v as? String { return ["1", "true", "up", "enabled"].contains(s.lowercased()) }
        return false
    }
}

/// Turns the router's JSON into a value-free outline: field names and types only, safe to share.
func outline(_ v: Any?, indent: String = "") -> String {
    switch v {
    case let d as [String: Any]:
        return d.keys.sorted().map { key in
            let child = d[key]
            if child is [String: Any] || child is [Any] { return "\(indent)\(key):\n" + outline(child, indent: indent + "  ") }
            return "\(indent)\(key): \(typeName(child))"
        }.joined(separator: "\n")
    case let a as [Any]:
        return a.first.map { "\(indent)[\(a.count)]\n" + outline($0, indent: indent + "  ") } ?? "\(indent)[0]"
    default:
        return indent + typeName(v)
    }
}

private func typeName(_ v: Any?) -> String {
    switch v {
    case is String: return "string"
    case let n as NSNumber: return CFGetTypeID(n) == CFBooleanGetTypeID() ? "bool" : "number"
    default: return "null"
    }
}

/// Полоса сети Wi-Fi: по стандартам (a/ac/ax → 5 ГГц) надёжнее позиционной эвристики; иначе по позиции.
private func wifiBand(_ item: [String: Any], index: Int, count: Int) -> String {
    let std = Field.str(item["OperatingStandards"]).lowercased()
    if std.contains("ac") || std.contains("ax") || std.split(separator: ",").contains("a") { return "5 ГГц" }
    if std.contains("b") || std.contains("g") { return "2,4 ГГц" }
    return index < count / 2 ? "2,4 ГГц" : "5 ГГц"
}

/// Выводит тип устройства из DHCP vendor class и имени хоста. Прошивка не отдаёт тип напрямую.
public func deviceType(vendorClass: String, hostName: String) -> String {
    let v = vendorClass.lowercased()
    let h = hostName.lowercased()
    func any(_ s: String, _ needles: [String]) -> Bool { needles.contains { s.contains($0) } }
    if any(v, ["printer", "xerox", "epson", "canon"]) || any(h, ["printer", "xrx", "xerox", "phaser", "печат"]) { return "Принтер" }
    if any(v, ["atvbox", "roku", "appletv", "shield"]) || any(h, ["tv", "appletv", "chromecast", "shield", "roku"]) { return "ТВ-приставка" }
    if any(h, ["iphone", "pixel", "galaxy", "redmi", "oneplus", "phone", "телефон"]) { return "Телефон или планшет" }
    if any(h, ["ipad", "mediapad", "tab"]) { return "Телефон или планшет" }
    if v.hasPrefix("android-dhcp") || v.contains(":android:") { return "Телефон или планшет" }
    if any(v, ["msft", "windows"]) || any(h, ["macbook", "imac", "-pc", "desktop", "mbp", "pc"]) { return "Компьютер" }
    if any(v, ["udhcp", "busybox", "dhcpcd"]) || any(h, ["vacuum", "esp", "shelly", "tuya", "sensor", "cam", "iot"]) { return "IoT-устройство" }
    if h.contains("mac") { return "Компьютер" }
    return "Неизвестно"
}

/// Все непустые поля устройства как строки — для раздела «Все поля»; технические идентификаторы скрыты.
private func deviceDetails(_ item: [String: Any]) -> [String: String] {
    let hidden: Set<String> = ["ClientDuid", "ClientID", "icon"]
    var out: [String: String] = [:]
    for (k, v) in item where !hidden.contains(k) {
        let str: String
        switch v {
        case let b as NSNumber where CFGetTypeID(b) == CFBooleanGetTypeID(): str = b.boolValue ? "да" : "нет"
        case let n as NSNumber: str = n.stringValue
        case let text as String: str = text
        default: str = ""
        }
        let keep = !str.isEmpty && (str != "0" || k.contains("Bytes") || k.contains("Rate"))
        if keep { out[k] = str }
    }
    return out
}

/// Сеть устройства: проводная — отдельная подпись, Wi-Fi — SSID той же полосы и роли (основная/гостевая).
private func networkName(connection: String, guest: Bool, wifi: [WifiNetwork]) -> String {
    guard connection.lowercased().contains("wi-fi") else {
        return connection.isEmpty ? "" : "Проводная сеть"
    }
    let band = connection.contains("2.4") || connection.contains("2,4") ? "2,4 ГГц" : "5 ГГц"
    if let match = wifi.first(where: { $0.band == band && $0.guest == guest }) { return match.ssid }
    return "Wi-Fi \(band)" + (guest ? " (гостевая)" : "")
}

/// Нормализует MAC к виду AA:BB:CC:DD:EE:FF (принимает разделители ":" и "-"), или nil если неверный.
public func normalizeMAC(_ s: String) -> String? {
    let parts = s.uppercased().replacingOccurrences(of: "-", with: ":").split(separator: ":", omittingEmptySubsequences: false)
    guard parts.count == 6, parts.allSatisfy({ $0.count == 2 && $0.allSatisfy(\.isHexDigit) }) else { return nil }
    return parts.joined(separator: ":")
}

/// Человекочитаемый объём: 1536 → «1,5 КБ», 1788431209 → «1,7 ГБ».
public func formatBytes(_ bytes: Int) -> String {
    guard bytes > 0 else { return "0 Б" }
    let units = ["Б", "КБ", "МБ", "ГБ", "ТБ"]
    var value = Double(bytes)
    var i = 0
    while value >= 1024, i < units.count - 1 { value /= 1024; i += 1 }
    return i == 0 ? "\(bytes) Б" : String(format: "%.1f %@", value, units[i]).replacingOccurrences(of: ".", with: ",")
}

/// Время ЧЧ:ММ в минуты от полуночи, или nil если формат неверный. Допускает 24:00 как конец суток.
public func minutesOfTime(_ s: String) -> Int? {
    let parts = s.split(separator: ":", omittingEmptySubsequences: false)
    guard parts.count == 2, let h = Int(parts[0]), let m = Int(parts[1]),
          parts[0].allSatisfy(\.isNumber), parts[1].allSatisfy(\.isNumber),
          (0...24).contains(h), (0...59).contains(m), !(h == 24 && m > 0) else { return nil }
    return h * 60 + m
}

/// Приводит ЧЧ:ММ к виду с ведущими нулями, или nil если формат неверный.
public func normalizeTime(_ s: String) -> String? {
    guard let total = minutesOfTime(s) else { return nil }
    return String(format: "%02d:%02d", total / 60, total % 60)
}

public func parseParental(_ objects: [[String: Any]]) -> ParentalControl {
    guard let head = objects.first else { return ParentalControl(enabled: false, maxProfiles: 0, profiles: []) }
    let profiles = objects.dropFirst().map { item -> ParentalProfile in
        let macs = Field.str(item["MACAddressList"]).split(whereSeparator: { $0 == "," }).map { $0.replacingOccurrences(of: ":", with: "-").uppercased() }
        let days = Field.str(item["Days"]).split(separator: ",").map(String.init)
        let times = Field.str(item["TimeRangeList"]).split(separator: ",").map(String.init)
        return ParentalProfile(
            index: Field.int(item["Index"]), name: Field.str(item["pcpName"]), enabled: Field.bool(item["pcpEnable"]),
            macs: macs, days: days, times: times,
            blocksSites: !Field.str(item["URLFilter"]).isEmpty, blocksServices: !Field.str(item["NetworkServiceList"]).isEmpty)
    }
    return ParentalControl(enabled: Field.bool(head["PrentalCtlEnable"]), maxProfiles: Field.int(head["MaxLenPrentalCtlPrf"]), profiles: profiles)
}

public func parseRouting(static staticObjs: [[String: Any]], dns dnsObjs: [[String: Any]], policy policyObjs: [[String: Any]], rip ripObjs: [[String: Any]]) -> Routing {
    let staticRoutes = staticObjs.enumerated().map { i, o -> RouteItem in
        let v6 = Field.str(o["ipver"]) == "IPv6"
        let dest = v6 ? Field.str(o["DestIPPrefix"]) : "\(Field.str(o["DestIPAddress"])) / \(Field.str(o["DestSubnetMask"]))"
        let gw = v6 ? Field.str(o["NextHop"]) : Field.str(o["GatewayIPAddress"])
        return RouteItem(id: i, title: Field.str(o["Name"]).isEmpty ? dest : Field.str(o["Name"]),
                         subtitle: "в \(dest)" + (gw.isEmpty ? "" : " через \(gw)"), enabled: Field.bool(o["Enable"]))
    }
    // id уникален по всему экрану (у групп свои диапазоны), иначе SwiftUI путает строки разных групп.
    let dnsRoutes = dnsObjs.enumerated().map { i, o in
        RouteItem(id: 100 + i, title: Field.str(o["DomainName"]), subtitle: "запросы DNS через выбранный интерфейс", enabled: Field.bool(o["Enable"]))
    }
    let policyRoutes = policyObjs.enumerated().map { i, o -> RouteItem in
        let proto = Field.str(o["Protocol"]); let src = Field.str(o["SourceIpAddress"])
        let from = [src.isEmpty ? "" : "IP \(src)", Field.str(o["SourceMacAddress"]).isEmpty ? "" : "MAC"].filter { !$0.isEmpty }.joined(separator: ", ")
        return RouteItem(id: 200 + i, title: Field.str(o["RouteName"]),
                         subtitle: (from.isEmpty ? "любой источник" : "от \(from)") + (proto == "ALL" || proto.isEmpty ? "" : ", \(proto)"), enabled: Field.bool(o["Enable"]))
    }
    let rip = ripObjs.enumerated().map { i, o -> RouteItem in
        let iface = Field.str(o["Interface"]).components(separatedBy: ".").last ?? ""
        return RouteItem(id: 300 + i, title: iface.isEmpty ? "RIP" : "Интерфейс \(iface)",
                         subtitle: "\(Field.str(o["Version"])) · \(Field.str(o["RipOpMode"]))", enabled: Field.bool(o["Enable"]))
    }
    return Routing(staticRoutes: staticRoutes, dnsRoutes: dnsRoutes, policyRoutes: policyRoutes, rip: rip)
}

public func parseSnapshot(status: [String: Any], hosts: [String: Any]) -> RouterSnapshot {
    let device = Field.dict(status["DeviceInfo"])
    // На реальном роутере LAN приходит как Srv="Default", Link="None" — исключаем из выбора WAN.
    let isLan: ([String: Any]) -> Bool = {
        let srv = Field.str($0["X_ZYXEL_SrvName"]).uppercased()
        return srv == "LAN" || srv == "DEFAULT" || Field.str($0["Name"]).uppercased() == "LAN" || Field.str($0["LinkType"]) == "None"
    }
    let wans = Field.list(status["WanLanInfo"]).filter { !isLan($0) && Field.str($0["Status"]) == "Up" }
    let wan = wans.first { Field.bool($0["DefaultGateway"]) } ?? wans.first { !Field.str($0["LinkType"]).isEmpty }
    let pppoe = ["PPPoE", "PPPoA", "PPtPtoPPPoA"].contains(Field.str(wan?["Encapsulation"]))
    let dsl = Field.list(status["DslChannelInfo"]).first { Field.str($0["Status"]) == "Up" }

    let rawWifi = Field.list(status["WiFiInfo"])
    let wifi: [WifiNetwork] = rawWifi.enumerated().compactMap { index, item in
        let ssid = Field.str(item["SSID"])
        guard !ssid.isEmpty else { return nil }
        return WifiNetwork(
            ssid: ssid, band: wifiBand(item, index: index, count: rawWifi.count), enabled: Field.bool(item["Enable"]),
            channel: Field.str(item["Channel"]), security: Field.str(item["ModeEnabled"]), guest: index % 2 == 1)
    }

    let devices: [RouterDevice] = Field.list(hosts["lanhosts"]).map { item in
        let mac = Field.str(item["PhysAddress"]).uppercased()
        let names = ["curHostName", "DeviceName", "HostName"].map { Field.str(item[$0]) }
        // Layer1Interface бывает "Device.WiFi.SSID.N" и "WiFi.SSID.N" — берём число после последнего "SSID.".
        let ssidIndex = Field.str(item["Layer1Interface"]).components(separatedBy: "SSID.").last.flatMap { Int($0) } ?? 0
        let connection = Field.str(item["X_ZYXEL_ConnectionType"])
        let guest = [2, 3, 4, 6, 7, 8].contains(ssidIndex)
        let host = names.first { !$0.isEmpty } ?? ""
        let vendorClass = Field.str(item["VendorClassID"]) == "*" ? "" : Field.str(item["VendorClassID"])
        return RouterDevice(
            name: host.isEmpty ? mac : host, ip: Field.str(item["IPAddress"]), mac: mac,
            connection: connection, online: Field.bool(item["Active"]), guest: guest,
            network: networkName(connection: connection, guest: guest, wifi: wifi),
            type: deviceType(vendorClass: vendorClass, hostName: host),
            vendorClass: vendorClass, signal: Field.int(item["X_ZYXEL_SignalStrength"]), snr: Field.int(item["X_ZYXEL_SNR"]),
            phyRate: Field.int(item["X_ZYXEL_PhyRate"]), wifiStandard: Field.str(item["X_ZYXEL_OperatingStandard"]),
            bytesDown: Field.int(item["X_ZYXEL_BytesReceived"]), bytesUp: Field.int(item["X_ZYXEL_BytesSent"]),
            ipv6: !Field.str(item["IPAddress6"]).isEmpty, staticIP: Field.bool(item["dhcp4StaticAddrEnable"]) || !Field.str(item["staticIP"]).isEmpty,
            leaseRemaining: Field.int(item["LeaseTimeRemaining"]), blocked: Field.bool(item["Internet_Blocking_Enable"]),
            details: deviceDetails(item), lastSeen: 0)
    }.sorted { ($0.online ? 0 : 1, $0.name.lowercased()) < ($1.online ? 0 : 1, $1.name.lowercased()) }

    let schema = "cardpage_status:\n" + outline(status, indent: "  ") + "\n\nlanhosts:\n" + outline(hosts, indent: "  ")
    return RouterSnapshot(
        model: Field.str(device["ModelName"]), firmware: Field.str(device["SoftwareVersion"]), uptime: Field.int(device["UpTime"]),
        internetUp: wan != nil, linkType: Field.str(wan?["LinkType"]), encapsulation: Field.str(wan?["Encapsulation"]),
        wanIP: Field.str(Field.list(wan?["IPv4Address"]).first?["IPAddress"]), dns: Field.str(Field.list(wan?["dnsv4Server"]).first?["DNSServer"]),
        internetUptime: Field.int(wan?[pppoe ? "pppoeConnectionUpTime" : "ipoeConnectionUpTime"]),
        downKbps: Field.int(dsl?["DownstreamCurrRate"]), upKbps: Field.int(dsl?["UpstreamCurrRate"]), devices: devices, wifi: wifi, schema: schema)
}
