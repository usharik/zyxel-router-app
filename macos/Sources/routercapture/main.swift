import Foundation
import RouterKit

// Снимает реальный формат ответов роутера в санитизированные фикстуры и печатает отчёт о формате.
// Запуск: ROUTER_PASSWORD=... routercapture <ip> [--write-test]
// Чтения безопасны. --write-test дополнительно создаёт ВЫКЛЮЧЕННЫЙ профиль с устройством
// "home media station", проверяет round-trip и УДАЛЯЕТ его (ничего не блокирует, обратимо).

let args = CommandLine.arguments.dropFirst()
let address = args.first(where: { !$0.hasPrefix("--") }) ?? "192.168.1.1"
let doWrite = args.contains("--write-test")
guard let password = ProcessInfo.processInfo.environment["ROUTER_PASSWORD"], !password.isEmpty else {
    FileHandle.standardError.write(Data("ROUTER_PASSWORD не задан\n".utf8)); exit(2)
}
let outDir = ProcessInfo.processInfo.environment["CAPTURE_DIR"] ?? "fixtures/real"
let user = ProcessInfo.processInfo.environment["ROUTER_USER"] ?? "admin"

// Ключи, значения которых чистим; формат сохраняем, секреты — нет.
let secretKeys: Set<String> = ["wifiPassword", "WEPKey", "WEP64Key1", "WEP64Key2", "WEP64Key3", "WEP64Key4", "WEPKey2", "WEPKey3", "WEPKey4", "AutoGenPSKValue", "PskDisplay", "Input_Passwd", "sessionkey"]

func typeName(_ v: Any) -> String {
    switch v {
    case is String: return "string"
    case let n as NSNumber: return CFGetTypeID(n) == CFBooleanGetTypeID() ? "bool" : "number"
    case is [Any]: return "array"
    case is [String: Any]: return "object"
    case is NSNull: return "null"
    default: return "?"
    }
}

// Санитизация: пароли → "<redacted>", MAC → формат сохранён но обезличен, SSID/имена/IP → generic.
var macSeq = 0
var macMap: [String: String] = [:]
func sanitize(_ v: Any, key: String = "") -> Any {
    if let d = v as? [String: Any] {
        var out: [String: Any] = [:]
        for (k, val) in d { out[k] = secretKeys.contains(k) ? "<redacted>" : sanitize(val, key: k) }
        return out
    }
    if let a = v as? [Any] { return a.map { sanitize($0, key: key) } }
    if let s = v as? String {
        // MAC: сохранить разделитель и длину, обезличить байты (важен ФОРМАТ, не значение).
        if s.range(of: "^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$", options: .regularExpression) != nil {
            if let m = macMap[s] { return m }
            macSeq += 1
            let sep = s.contains("-") ? "-" : ":"
            let m = ["14", "33", "75", "00", "00", String(format: "%02X", macSeq)].joined(separator: sep)
            macMap[s] = m; return m
        }
        if key == "SSID" { return "Net-\(abs(s.hashValue) % 100)" }
        if key.contains("IPAddress") || key == "DNSServer" || key.contains("Gateway") { return s.isEmpty ? s : "198.51.100.\(abs(s.hashValue) % 200 + 1)" }
        if key == "HostName" || key == "curHostName" || key == "DeviceName" { return s.isEmpty ? s : "device-\(abs(s.hashValue) % 100)" }
        return s
    }
    return v
}

func write(_ name: String, _ obj: Any) {
    try? FileManager.default.createDirectory(atPath: outDir, withIntermediateDirectories: true)
    let data = try! JSONSerialization.data(withJSONObject: sanitize(obj), options: [.prettyPrinted, .sortedKeys])
    try! data.write(to: URL(fileURLWithPath: "\(outDir)/\(name).json"))
}

let client = try RouterClient(address: address)
do { try await client.login(username: user, password: password) }
catch { FileHandle.standardError.write(Data("Вход не удался: \(error.localizedDescription)\n".utf8)); exit(3) }
print("вход выполнен, снимаю формат (значения не печатаю)\n")

let extraOids = (ProcessInfo.processInfo.environment["CAPTURE_OIDS"] ?? "").split(separator: ",").map(String.init)
let oids = ["cardpage_status", "lanhosts", "paren_ctl", "static_route", "dns_route", "policy_route", "rip"] + extraOids
for oid in oids {
    do {
        let raw = try await client.rawDAL(oid)
        write(oid, raw)
        let objects = (raw["Object"] as? [[String: Any]]) ?? []
        print("== \(oid): result=\(raw["result"] ?? "?"), Object=\(objects.count) шт")
    } catch { print("== \(oid): ОШИБКА \(error.localizedDescription)") }
}

// Произвольные GET-пути (журнал и т.п.): CAPTURE_PATHS="имя=путь;имя2=путь2"
for pair in (ProcessInfo.processInfo.environment["CAPTURE_PATHS"] ?? "").split(separator: ";") {
    let kv = pair.split(separator: "=", maxSplits: 1).map(String.init)
    guard kv.count == 2 else { continue }
    do {
        let raw = try await client.rawGet(kv[1])
        write(kv[0], raw ?? [:])
        if let d = raw as? [String: Any] {
            print("== \(kv[0]): ключи=\(d.keys.sorted().prefix(12))")
        } else if let a = raw as? [Any] {
            print("== \(kv[0]): массив из \(a.count)")
        }
    } catch { print("== \(kv[0]): ОШИБКА \(error.localizedDescription)") }
}

// Отчёт о формате родительского контроля — ровно то, что беспокоило ревьюеров.
if let pc = try? await client.rawDAL("paren_ctl"), let objs = pc["Object"] as? [[String: Any]] {
    print("\n--- ФОРМАТ paren_ctl ---")
    for (i, o) in objs.enumerated() {
        if i == 0 { print("  [0] общий: PrentalCtlEnable тип=\(o["PrentalCtlEnable"].map(typeName) ?? "нет"), MaxLen=\(o["MaxLenPrentalCtlPrf"] ?? "нет")"); continue }
        let days = o["Days"]; let mac = (o["MACAddressList"] as? String) ?? ""
        let macFmt = mac.contains("-") ? "дефисы" : (mac.contains(":") ? "двоеточия" : "иное/пусто")
        print("  профиль[\(i)]: Days тип=\(days.map(typeName) ?? "нет") значение=\(daysShape(days)), MAC=\(macFmt), TimeRangeList тип=\((o["TimeRangeList"]).map(typeName) ?? "нет") интервалов=\(((o["TimeRangeList"] as? String)?.split(separator: ",").count) ?? 0), pcpEnable тип=\(o["pcpEnable"].map(typeName) ?? "нет")")
    }
}

func daysShape(_ v: Any?) -> String {
    if let s = v as? String { return "строка(\(s.split(separator: ",").count) дн)" }
    if let a = v as? [Any] { return "массив(\(a.count) дн)" }
    return "—"
}

// Поиск устройства "home media station".
var mediaMac: String?
var mediaName: String?
if let hosts = try? await client.rawDAL("lanhosts"), let lan = (hosts["Object"] as? [[String: Any]])?.first?["lanhosts"] as? [[String: Any]] {
    print("\n--- УСТРОЙСТВА (\(lan.count)) ---")
    for h in lan {
        let names = ["curHostName", "DeviceName", "HostName"].compactMap { h[$0] as? String }.filter { !$0.isEmpty }
        let name = names.first ?? "(без имени)"
        let active = (h["Active"] as? NSNumber)?.boolValue ?? ((h["Active"] as? String) == "1")
        // Цель задаётся MEDIA_MATCH (подстроки через запятую, все должны встретиться); по умолчанию "media".
        let needles = (ProcessInfo.processInfo.environment["MEDIA_MATCH"] ?? "media").lowercased().split(separator: ",").map(String.init)
        if mediaMac == nil, names.contains(where: { n in needles.allSatisfy { n.lowercased().contains($0) } }) {
            mediaMac = (h["PhysAddress"] as? String); mediaName = name
        }
        print("  \(active ? "●" : "○") \(name) [\(h["X_ZYXEL_ConnectionType"] ?? "?")]")
    }
}
print(mediaMac != nil ? "\nНайдено устройство для теста: \(mediaName!)" : "\nУстройство с 'media' в имени не найдено")

if doWrite {
    guard let mac = mediaMac else { print("--write-test пропущен: нет устройства media"); await client.logout(); exit(0) }
    print("\n=== ОБРАТИМЫЙ ТЕСТ ЗАПИСИ (профиль ВЫКЛЮЧЕН, ничего не блокирует) ===")
    let name = "TEST-media-\(Int(Date().timeIntervalSince1970) % 100000)"
    let before = try await client.parentalControl()
    try await client.saveProfile(index: nil, name: name, macs: [mac], days: ["Mon"], timeRangeList: "09:00-10:00", enabled: false)
    // Снимаем реальную форму профиля в фикстуру, пока он существует.
    if let rawPc = try? await client.rawDAL("paren_ctl") { write("paren_ctl_with_profile", rawPc) }
    let after = try await client.parentalControl()
    if let created = after.profiles.first(where: { $0.name == name }) {
        print("  создан: \(created.name) on=\(created.enabled) устройств=\(created.macs.count) дни=\(created.days.joined(separator: "/")) время=\(created.times.joined(separator: ","))")
        let okDays = created.days == ["Mon"]
        let okMac = created.macs.count == 1
        print("  round-trip дни: \(okDays ? "OK" : "РАСХОЖДЕНИЕ \(created.days)"), устройство: \(okMac ? "OK" : "РАСХОЖДЕНИЕ")")
        try await client.deleteProfile(index: created.index)
        let final = try await client.parentalControl()
        let gone = !final.profiles.contains { $0.name == name }
        print("  удалён: \(gone ? "OK, роутер в исходном состоянии" : "ВНИМАНИЕ: профиль остался!")")
        print("  профилей было \(before.profiles.count), стало \(final.profiles.count)")
    } else {
        print("  ОШИБКА: созданный профиль не найден — формат записи роутер не принял")
    }
}

if ProcessInfo.processInfo.environment["CAPTURE_LOG"] == "1" {
    let log = try await client.systemLog()
    print("\n--- ЖУРНАЛ: \(log.count) записей (текст не печатаю) ---")
    print("распознано с датой: \(log.filter { $0.month > 0 }.count)")
    print("по важности:", logCountsBy(log) { $0.severity }.prefix(8).map { "\($0.label)=\($0.count)" })
    print("топ категорий:", logCountsBy(log) { $0.category }.prefix(6).map { "\($0.label)=\($0.count)" })
    for p in LogPeriod.allCases { print("по \(p.rawValue.lowercased())м:", logAggregate(log, by: p).prefix(5).map { "\($0.label)=\($0.count)" }) }
}

await client.logout()
print("\nфикстуры в \(outDir)/ (санитизированы), выход")
