import Foundation
import RouterKit

// Мини-харнесс без XCTest (его нет без Xcode). Падает с ненулевым кодом при провале.
var failures = 0
func check(_ cond: Bool, _ name: String, _ detail: String = "") {
    if cond { print("ok   - \(name)") } else { failures += 1; print("FAIL - \(name) \(detail)") }
}
func eq<T: Equatable>(_ a: T, _ b: T, _ name: String) { check(a == b, name, "(\(a) != \(b))") }

// MARK: networkName через parseSnapshot
let status: [String: Any] = [
    "DeviceInfo": ["ModelName": "VMG", "SoftwareVersion": "V1", "UpTime": 100],
    "WanLanInfo": [["Status": "Up", "LinkType": "PTM", "Encapsulation": "PPPoE", "DefaultGateway": true,
                    "IPv4Address": [["IPAddress": "203.0.113.7"]], "dnsv4Server": [["DNSServer": "1.1.1.1"]], "pppoeConnectionUpTime": 60]],
    "DslChannelInfo": [["Status": "Up", "DownstreamCurrRate": 98304, "UpstreamCurrRate": 20480]],
    "WiFiInfo": [
        ["SSID": "Home", "Enable": 1, "Channel": 6, "OperatingStandards": "b,g,n", "ModeEnabled": "WPA2-Personal", "wifiPassword": "SECRET24"],
        ["SSID": "Guest", "Enable": 0, "Channel": 6, "OperatingStandards": "b,g,n", "ModeEnabled": "WPA2-Personal", "wifiPassword": "SECRETG"],
        ["SSID": "Home-5G", "Enable": true, "Channel": 44, "OperatingStandards": "a,n,ac", "ModeEnabled": "WPA2-Personal", "wifiPassword": "SECRET5"],
        ["SSID": "Guest-5G", "Enable": false, "Channel": 44, "OperatingStandards": "a,n,ac", "ModeEnabled": "None", "wifiPassword": ""],
    ],
]
let hosts: [String: Any] = ["lanhosts": [
    ["Active": true, "curHostName": "mac", "PhysAddress": "14:33:75:00:00:01", "IPAddress": "192.168.1.52", "X_ZYXEL_ConnectionType": "Wi-Fi 5GHz", "Layer1Interface": "Device.WiFi.SSID.5"],
    ["Active": 1, "HostName": "tv", "PhysAddress": "14:33:75:00:00:02", "IPAddress": "192.168.1.60", "X_ZYXEL_ConnectionType": "Ethernet", "Layer1Interface": "Device.Ethernet.Interface.1"],
    ["Active": 1, "DeviceName": "phone", "PhysAddress": "14:33:75:00:00:03", "IPAddress": "192.168.2.33", "X_ZYXEL_ConnectionType": "Wi-Fi 2.4GHz", "Layer1Interface": "Device.WiFi.SSID.2"],
]]
let snap = parseSnapshot(status: status, hosts: hosts)
let byName = Dictionary(uniqueKeysWithValues: snap.devices.map { ($0.name, $0) })
eq(byName["mac"]?.network, "Home-5G", "device mac → Home-5G")
eq(byName["tv"]?.network, "Проводная сеть", "device tv → проводная")
eq(byName["phone"]?.network, "Guest", "device phone → Guest (гостевая 2.4)")
check(byName["phone"]?.guest ?? false, "phone помечен гостевым")
eq(snap.wanIP, "203.0.113.7", "wanIP")
check(snap.internetUp, "internetUp")
// Полоса по стандартам: 5G-сети определяются по 'a/ac' даже если бы позиция подвела.
eq(snap.wifi.first(where: { $0.ssid == "Home-5G" })?.band, "5 ГГц", "Home-5G band=5")
eq(snap.wifi.first(where: { $0.ssid == "Home" })?.band, "2,4 ГГц", "Home band=2.4")

// MARK: диагностика не содержит значений (паролей)
check(!snap.schema.contains("SECRET24"), "пароль Wi-Fi НЕ в диагностике")
check(!snap.schema.contains("203.0.113.7"), "IP НЕ в диагностике")
check(snap.schema.contains("wifiPassword"), "имя поля wifiPassword в диагностике есть")

// MARK: устойчивость к мусору
let snapGarbage = parseSnapshot(status: ["WanLanInfo": [NSNull(), ["Status": "Up", "LinkType": "PTM", "DefaultGateway": true, "IPv4Address": [["IPAddress": "10.0.0.2"]]]], "WiFiInfo": [], "DeviceInfo": [:], "DslChannelInfo": []], hosts: [:])
eq(snapGarbage.wanIP, "10.0.0.2", "null-элемент в массиве не теряет весь массив")
check(snapGarbage.internetUp, "internetUp при null-элементе")
let snapEmpty = parseSnapshot(status: [:], hosts: [:])
eq(snapEmpty.devices.count, 0, "пустой снимок: нет устройств")
eq(snapEmpty.wifi.count, 0, "пустой снимок: нет Wi-Fi")

// MARK: родительский контроль
let pc = parseParental([
    ["PrentalCtlEnable": true, "MaxLenPrentalCtlPrf": 20],
    ["Index": 1, "pcpEnable": true, "pcpName": "Дети", "MACAddressList": "14:33:75:00:00:03",
     "Days": "Mon,Tue,Fri", "TimeRangeList": "21:00-23:59", "URLFilter": "tiktok.com", "NetworkServiceList": ""],
])
check(pc.enabled, "родительский контроль включён")
eq(pc.maxProfiles, 20, "maxProfiles")
eq(pc.profiles.count, 1, "один профиль")
eq(pc.profiles.first?.days ?? [], ["Mon", "Tue", "Fri"], "дни профиля")
eq(pc.profiles.first?.macs ?? [], ["14-33-75-00-00-03"], "MAC профиля (дефисы)")
check(pc.profiles.first?.blocksSites ?? false, "профиль блокирует сайты")
eq(parseParental([]).profiles.count, 0, "пустой родительский контроль")

// MARK: маршруты, уникальные id
let r = parseRouting(
    static: [["Index": 0, "Enable": true, "Name": "office", "ipver": "IPv4", "DestIPAddress": "10.0.0.0", "DestSubnetMask": "255.255.255.0", "GatewayIPAddress": "192.168.1.2"]],
    dns: [["Index": 1, "Enable": true, "DomainName": "work.example.com"]],
    policy: [["Index": 0, "Enable": true, "RouteName": "vpn", "SourceIpAddress": "192.168.1.60", "Protocol": "ALL"]],
    rip: [["Interface": "Device.IP.Interface.1", "Version": "RIPv2", "RipOpMode": "Passive", "Enable": false]])
eq(r.staticRoutes.first?.title, "office", "static title")
eq(r.dnsRoutes.first?.title, "work.example.com", "dns title")
eq(r.policyRoutes.first?.title, "vpn", "policy title")
eq(r.rip.first?.title, "Интерфейс 1", "rip title")
let allRoutes: [RouteItem] = r.staticRoutes + r.dnsRoutes + r.policyRoutes + r.rip
let ids = allRoutes.map { $0.id }
eq(ids.count, Set(ids).count, "id маршрутов уникальны")

// MARK: время — нормализация и границы
eq(normalizeTime("9:00"), "09:00", "normalize 9:00 → 09:00")
eq(normalizeTime("09:05"), "09:05", "normalize 09:05")
eq(normalizeTime("24:00"), "24:00", "normalize 24:00 (конец суток)")
check(normalizeTime("24:01") == nil, "24:01 неверно")
check(normalizeTime("9:60") == nil, "9:60 неверно")
check(normalizeTime("25:00") == nil, "25:00 неверно")
check(normalizeTime("09") == nil, "09 без минут неверно")
check(normalizeTime("ab:cd") == nil, "нечисло неверно")
// Главная проверка строкового бага: 9:00 < 10:00 по минутам, а не по строке.
check((minutesOfTime("9:00") ?? 0) < (minutesOfTime("10:00") ?? 0), "9:00 раньше 10:00 (минуты)")
check((minutesOfTime("09:30") ?? 0) < (minutesOfTime("09:45") ?? 0), "09:30 раньше 09:45")

// MARK: приватные IPv4
check(RouterClient.isPrivateIPv4("192.168.1.1"), "192.168.1.1 приватный")
check(RouterClient.isPrivateIPv4("10.0.0.1"), "10.0.0.1 приватный")
check(RouterClient.isPrivateIPv4("172.16.5.5"), "172.16.5.5 приватный")
check(!RouterClient.isPrivateIPv4("8.8.8.8"), "8.8.8.8 не приватный")
check(!RouterClient.isPrivateIPv4("172.32.0.1"), "172.32.0.1 не приватный")
check(!RouterClient.isPrivateIPv4("1.2.3"), "1.2.3 не IPv4")
check(!RouterClient.isPrivateIPv4(""), "пусто не IPv4")

// MARK: крипто-самотест (AES round-trip с 32-байтным IV + pkcs1 отвергает мусор)
check(RouterClient.selfTestCrypto(), "крипто-самотест")

// MARK: разбор реальных (санитизированных) фикстур роутера
func loadFixture(_ name: String) -> [String: Any]? {
    // Путь к репозиторию относительно этого файла: .../macos/Sources/RouterKitTests/main.swift
    let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    let url = root.appendingPathComponent("fixtures/real/\(name).json")
    guard let data = try? Data(contentsOf: url) else { return nil }
    return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
}
if let card = loadFixture("cardpage_status"), let lan = loadFixture("lanhosts") {
    let cardObj = (card["Object"] as? [[String: Any]])?.first ?? [:]
    let lanObj = (lan["Object"] as? [[String: Any]])?.first ?? [:]
    let real = parseSnapshot(status: cardObj, hosts: lanObj)
    check(real.internetUp, "реальный снимок: интернет Up (VDSL)")
    check(!real.wanIP.isEmpty, "реальный снимок: есть внешний IP")
    check(real.devices.count > 5, "реальный снимок: устройства разобраны (\(real.devices.count))")
    check(real.wifi.contains { $0.band == "5 ГГц" } && real.wifi.contains { $0.band == "2,4 ГГц" }, "реальный снимок: обе полосы Wi-Fi")
    check(!real.schema.contains("198.51.100") && !real.schema.contains("14:33:75"), "диагностика: значения реальных данных не попадают")
    // Устройство с Layer1Interface "WiFi.SSID.2" (без префикса) должно считаться гостевым.
    print("info - устройств в реальной фикстуре: \(real.devices.count), гостевых: \(real.devices.filter(\.guest).count)")
} else {
    print("skip - фикстуры cardpage/lanhosts не найдены")
}
if let pcFix = loadFixture("paren_ctl_with_profile"), let objs = pcFix["Object"] as? [[String: Any]] {
    let pc = parseParental(objs)
    eq(pc.maxProfiles, 12, "реальный предел профилей = 12")
    eq(pc.profiles.first?.days ?? [], ["Mon"], "реальный профиль: Days строка → [Mon]")
    eq(pc.profiles.first?.times ?? [], ["09:00-10:00"], "реальный профиль: TimeRangeList")
    check(pc.profiles.first?.macs.first?.contains("-") ?? false, "реальный MAC нормализован к дефисам для показа")
} else {
    print("skip - фикстура paren_ctl_with_profile не найдена")
}

// MARK: тип устройства (на реальных значениях VendorClassID)
eq(deviceType(vendorClass: "android-dhcp-13", hostName: ""), "Телефон или планшет", "android-dhcp → телефон/планшет")
eq(deviceType(vendorClass: "HUAWEI:android:QC_Reference_Phone", hostName: ""), "Телефон или планшет", "huawei android → телефон/планшет")
eq(deviceType(vendorClass: "XEROX Network Printer", hostName: ""), "Принтер", "XEROX → принтер")
eq(deviceType(vendorClass: "MSFT 5.0", hostName: ""), "Компьютер", "MSFT → компьютер")
eq(deviceType(vendorClass: "atvbox-4403-czsk", hostName: ""), "ТВ-приставка", "atvbox → ТВ")
eq(deviceType(vendorClass: "udhcp 1.36.0", hostName: ""), "IoT-устройство", "udhcp → IoT")
eq(deviceType(vendorClass: "", hostName: "MBP-aleks"), "Компьютер", "имя MBP → компьютер")
eq(deviceType(vendorClass: "", hostName: "iPhone"), "Телефон или планшет", "имя iPhone → телефон")
eq(deviceType(vendorClass: "", hostName: "xiaomi_vacuum"), "IoT-устройство", "имя vacuum → IoT")
eq(deviceType(vendorClass: "*", hostName: "неведомое"), "Неизвестно", "нет подсказок → неизвестно")

// MARK: форматирование объёма
eq(formatBytes(0), "0 Б", "0 байт")
eq(formatBytes(512), "512 Б", "512 Б")
eq(formatBytes(1536), "1,5 КБ", "1,5 КБ")
eq(formatBytes(1788431209), "1,7 ГБ", "1,7 ГБ")

// MARK: расширенные поля на реальном трафике (фикстура lanhosts)
if let lan = loadFixture("lanhosts"), let card = loadFixture("cardpage_status") {
    let cardObj = (card["Object"] as? [[String: Any]])?.first ?? [:]
    let lanObj = (lan["Object"] as? [[String: Any]])?.first ?? [:]
    let real = parseSnapshot(status: cardObj, hosts: lanObj)
    check(real.devices.contains { $0.bytesDown > 0 }, "реальный трафик: есть устройства с принятыми байтами")
    check(real.devices.contains { $0.bytesUp > 0 }, "реальный трафик: есть устройства с отправленными байтами")
    check(real.devices.contains { $0.signal > 0 && $0.signal <= 5 }, "реальный сигнал в диапазоне 1–5")
    check(real.devices.allSatisfy { !$0.details.isEmpty }, "у каждого устройства заполнены «все поля»")
    check(real.devices.contains { $0.type == "Принтер" }, "в реальных данных распознан принтер (XEROX)")
    check(real.devices.contains { $0.type == "Телефон или планшет" }, "в реальных данных распознан Android")
    let printer = real.devices.first { $0.vendorClass.lowercased().contains("xerox") }
    if let p = printer { eq(p.type, "Принтер", "устройство с VendorClass XEROX → Принтер") }
}

// MARK: разбор и агрегация журнала
let logRaw: [[String: Any]] = [["result": [
    "[dhcpd] Nov  8 16:54:14 daemon.info dnsmasq-dhcp: lease added",
    "[tr69] Nov  8 10:00:00 daemon.err cwmp: connection failed",
    "[dhcpc] Nov  7 09:00:00 daemon.debug udhcpc: renew",
    "[kernel] Oct 31 23:00:00 kern.warning kernel: oops",
    "",
]]]
let logEntries = parseLog(logRaw)
eq(logEntries.count, 4, "журнал: разобрано 4 записи (пустая пропущена)")
eq(logEntries[0].category, "dhcpd", "журнал: категория")
eq(logEntries[0].severity, "info", "журнал: важность info")
eq(logEntries[0].month, 11, "журнал: месяц=11")
eq(logEntries[0].day, 8, "журнал: день=8")
eq(logEntries[1].severity, "err", "журнал: err")
eq(logEntries[1].message, "connection failed", "журнал: сообщение")
let byDay = logAggregate(logEntries, by: .day)
eq(byDay.count, 3, "журнал: 3 разных дня")
eq(byDay.first?.count, 2, "журнал: свежий день (8 ноя) = 2 записи")
let byMonth = logAggregate(logEntries, by: .month)
eq(byMonth.first?.label, "ноя", "журнал: свежий месяц ноя")
eq(byMonth.first?.count, 3, "журнал: ноя = 3")
eq(byMonth.count, 2, "журнал: 2 месяца")
eq(logCountsBy(logEntries) { $0.severity }.reduce(0) { $0 + $1.count }, 4, "журнал: сумма по важности = 4")
eq(logCountsBy(logEntries) { $0.category }.first?.count, 1, "журнал: топ-категория 1")

// MARK: нормализация MAC
eq(normalizeMAC("aa:bb:cc:dd:ee:ff"), "AA:BB:CC:DD:EE:FF", "MAC двоеточия → верхний регистр")
eq(normalizeMAC("AA-BB-CC-DD-EE-FF"), "AA:BB:CC:DD:EE:FF", "MAC дефисы → двоеточия")
check(normalizeMAC("zz:bb:cc:dd:ee:ff") == nil, "MAC с не-hex → nil")
check(normalizeMAC("aa:bb:cc:dd:ee") == nil, "MAC из 5 групп → nil")
check(normalizeMAC("aabbccddeeff") == nil, "MAC без разделителей → nil")

// MARK: офлайн-устройство из истории
let off = RouterDevice.offline(mac: "AA:BB:CC:DD:EE:01", name: "PC", type: "Компьютер", connection: "Ethernet", lastSeen: 123)
check(!off.online, "офлайн-устройство: online=false")
eq(off.mac, "AA:BB:CC:DD:EE:01", "офлайн: mac")
eq(off.name, "PC", "офлайн: имя")
eq(off.network, "Проводная сеть", "офлайн: проводная сеть")
eq(off.lastSeen, 123, "офлайн: lastSeen")
let offWifi = RouterDevice.offline(mac: "AA:BB:CC:DD:EE:02", name: "", type: "", connection: "Wi-Fi 5GHz", lastSeen: 0)
eq(offWifi.name, "AA:BB:CC:DD:EE:02", "офлайн: имя из MAC если пусто")
eq(offWifi.network, "Wi-Fi", "офлайн: Wi-Fi сеть")

// MARK: Обезличивание
for k in ["SerialNumber", "X_ZYXEL_Ploam_Password", "X_ZYXEL_ProvisioningKey", "wifiPassword", "WEP64Key1", "WEPKey", "PskDisplay", "AutoGenPSKValue", "Input_Passwd", "sessionkey", "FirstUseDate", "X_ZYXEL_LoginBanner", "Location", "DomainName", "v4dns"] {
    check(isSensitiveKey(k), "обезличивание: \(k) скрывается")
}
for k in ["ModelName", "SoftwareVersion", "VendorClassID", "Days", "Enable", "IPAddress"] {
    check(!isSensitiveKey(k), "обезличивание: \(k) сохраняется")
}
eq(anonymizedIPv6("2a02:768:1:2::/64", sequence: 3), "2001:db8:3::/64", "IPv6: публичный префикс")
eq(anonymizedIPv6("fe80::1234:56ff:fe78:9abc", sequence: 10), "fe80::a", "IPv6: link-local")
check(anonymizedIPv6("14:33:75:00:00:01", sequence: 1) == nil, "IPv6: MAC не трогаем")
check(anonymizedIPv6("21:00-23:59", sequence: 1) == nil, "IPv6: интервал времени не трогаем")
check(anonymizedIPv6("21:00", sequence: 1) == nil, "IPv6: время не трогаем")
check(anonymizedIPv6("Device.IP.Interface.1", sequence: 1) == nil, "IPv6: путь TR-181 не трогаем")

func leakedKeys(_ v: Any, key: String = "") -> [String] {
    if let d = v as? [String: Any] { return d.flatMap { leakedKeys($0.value, key: $0.key) } }
    if let a = v as? [Any] { return a.flatMap { leakedKeys($0, key: key) } }
    if let s = v as? String, !s.isEmpty, s != "<redacted>", isSensitiveKey(key), !(key == "v4dns" && s.hasPrefix("198.51.100.")) { return [key] }
    return []
}
for name in ["cardpage_status", "lanhosts", "paren_ctl_with_profile"] {
    if let f = loadFixture(name) { eq(leakedKeys(f), [], "фикстура \(name) без личных данных") }
}

// MARK: Запись
let strDays = daysAsArray(["Days": "Mon, Tue,,Fri", "x": 1])
eq(strDays["Days"] as? [String] ?? [], ["Mon", "Tue", "Fri"], "Days: строка становится массивом")
eq(daysAsArray(["Days": ["Sat"]])["Days"] as? [String] ?? [], ["Sat"], "Days: массив не меняется")
check(daysAsArray(["x": 1])["Days"] == nil, "Days: отсутствие не добавляет поле")
eq(mergedLastSeen(online: true, previous: 100, now: 500), 500, "lastSeen: онлайн обновляется")
eq(mergedLastSeen(online: false, previous: 100, now: 500), 100, "lastSeen: неактивное сохраняет прежнее")

eq(bannerAfterPoll(nil, current: "связь", rebootNotice: false, writeError: nil), nil, "баннер: успешный опрос убирает ошибку связи")
eq(bannerAfterPoll(nil, current: "запись", rebootNotice: false, writeError: "запись"), "запись", "баннер: опрос не стирает ошибку записи")
eq(bannerAfterPoll("связь", current: "запись", rebootNotice: false, writeError: "запись"), "связь", "баннер: новая ошибка связи видна")
eq(bannerAfterPoll(nil, current: "ребут", rebootNotice: true, writeError: nil), "ребут", "баннер: перезагрузка не стирается")

var calls = 0, relogins = 0
let recovered = try await withSessionRecovery(retry: true, relogin: { relogins += 1 }) { () async throws -> String in
    calls += 1
    if calls == 1 { throw RouterError.sessionExpired }
    return "ok"
}
check(recovered == "ok" && calls == 2 && relogins == 1, "восстановление: повтор после входа")

calls = 0; relogins = 0
do {
    try await withSessionRecovery(retry: false, relogin: { relogins += 1 }) { calls += 1; throw RouterError.sessionExpired }
    check(false, "восстановление: без повтора должно бросить")
} catch { check(calls == 1 && relogins == 1 && error.localizedDescription.contains("Повторите"), "восстановление: неидемпотентное не повторяется") }

calls = 0
do {
    try await withSessionRecovery(retry: true, relogin: { throw RouterError.invalidCredentials }) { calls += 1; throw RouterError.sessionExpired }
    check(false, "восстановление: отказ должен дойти")
} catch { check(error as? RouterError == .invalidCredentials && calls == 1, "восстановление: отказ во входе не повторяет запись") }

calls = 0; relogins = 0
do {
    try await withSessionRecovery(retry: true, relogin: { relogins += 1 }) { calls += 1; throw RouterError.failed("x") }
    check(false, "восстановление: ошибка должна дойти")
} catch { check(relogins == 0 && calls == 1, "восстановление: прочие ошибки без повторного входа") }

// MARK: Диагностика
let pingOut = """
--- 1.1.1.1 ping statistics ---
30 packets transmitted, 27 packets received, 10.0% packet loss
round-trip min/avg/max/stddev = 9.1/11.0/21.9/3.2 ms
"""
let ping = parsePing(pingOut)
eq(ping?.received, 27, "ping: получено")
check(abs((ping?.lossPercent ?? 0) - 10) < 0.01, "ping: потери 10%")
eq(ping?.avgMs, 11.0, "ping: среднее")
eq(parsePing("ping: cannot resolve")?.sent, nil, "ping: мусор → nil")
eq(parsePing("5 packets transmitted, 0 packets received, 100.0% packet loss")?.lossPercent, 100, "ping: без ответа")
let digOut = ";; ->>HEADER<<- opcode: QUERY, status: NXDOMAIN, id: 1\n;; Query time: 19 msec\n"
eq(parseDig(digOut).status, "NXDOMAIN", "dig: статус"); eq(parseDig(digOut).ms, 19, "dig: время")
check(parseDig(digOut).ok, "dig: NXDOMAIN считается рабочим DNS")
check(!parseDig(";; connection timed out; no servers could be reached").ok, "dig: таймаут")

if let card = loadFixture("cardpage_status"), let lan = loadFixture("lanhosts") {
    let cardObj = (card["Object"] as? [[String: Any]])?.first ?? [:]
    let lanObj = (lan["Object"] as? [[String: Any]])?.first ?? [:]
    let traffic: [String: Any] = [
        "ipIface": [["Name": "LAN", "Status": "Up", "LowerLayers": "Ethernet.Link.1"], ["Name": "WAN", "Status": "Up", "LowerLayers": "PPP.Interface.1"]],
        "ipIfaceSt": [["ErrorsReceived": 0], [:]],
        "pppIfaceSt": [["BytesReceived": 5_000_000, "BytesSent": 1_000_000, "PacketsReceived": 900, "PacketsSent": 100]],
        "ethIface": [["X_ZYXEL_LanPort": "LAN1", "Status": "Up", "CurrentBitRate": 10, "MaxBitRate": 1000, "DuplexMode": "Full"],
                     ["X_ZYXEL_LanPort": "LAN2", "Status": "Down", "CurrentBitRate": 0, "MaxBitRate": 1000, "DuplexMode": "Auto"]],
        "ethIfaceSt": [["ErrorsReceived": 0], [:]],
        "wifiRadio": [["OperatingFrequencyBand": "2.4GHz", "Enable": true, "Status": "Up", "Channel": 1]],
    ]
    let now = Date(timeIntervalSince1970: 1_794_470_400)
    let input = RouterDiagInput(status: cardObj, hosts: lanObj, traffic: traffic,
                                mgmt: [["Name": "HTTPS", "Mode": "LAN_ONLY", "Port": 443], ["Name": "SSH", "Mode": "LAN_WAN", "Port": 22]],
                                firewall: ["Enable": true, "enableDos": true, "Level_GUI": "Low"], time: [:], parental: nil,
                                systemLog: [], securityLog: [])
    // Первый запуск: базовая линия устройств, без «новых устройств».
    let first = diagnose(router: input, routerError: nil, local: LocalProbe(), history: [], knownDevices: [:], routerAddress: "192.168.1.1", now: now)
    check(first.findings.contains { $0.area == "Порты LAN" && $0.text.contains("LAN1") && $0.level == .warn }, "диагностика: LAN1 на 10 Мбит/с")
    check(!first.findings.contains { $0.text.contains("LAN2") }, "диагностика: выключенный порт не оценивается")
    check(first.findings.contains { $0.level == .crit && $0.text.contains("SSH:22") }, "диагностика: управление из интернета — критично")
    check(!first.findings.contains { $0.area == "Устройства" }, "диагностика: первый запуск без новых устройств")
    check(!first.knownDevices.isEmpty, "диагностика: устройства запомнены")
    eq(first.metrics.wanRx, 5_000_000, "диагностика: байты WAN берутся с PPP-интерфейса")
    check(first.metrics.dslDown > 0, "диагностика: синхронизация DSL из фикстуры")
    check(!renderMarkdown(first).contains("<redacted>"), "диагностика: в отчёт не попадают скрытые поля")

    // Второй запуск через сутки: роутер работает меньше суток → перезагрузка; одно устройство «забыто» → новое.
    var prev = first.metrics
    prev.date = now.timeIntervalSince1970 - 86400
    prev.dslDown = first.metrics.dslDown * 2
    var known = first.knownDevices
    let forgotten = known.keys.sorted().first!
    known.removeValue(forKey: forgotten)
    let second = diagnose(router: input, routerError: nil, local: LocalProbe(), history: [prev, prev, prev], knownDevices: known,
                          routerAddress: "192.168.1.1", now: now.addingTimeInterval(Double(Field_uptime(cardObj)) + 3600))
    check(second.findings.contains { $0.area == "Система" && $0.text.contains("перезагружался") }, "диагностика: перезагрузка обнаружена")
    check(second.findings.contains { $0.area == "Устройства" && $0.text.contains(forgotten) }, "диагностика: новое устройство")
    check(second.findings.contains { $0.area == "DSL" && $0.text.contains("упала") }, "диагностика: падение синхронизации DSL")

    let down = diagnose(router: nil, routerError: "нет связи", local: LocalProbe(), history: [], knownDevices: [:], routerAddress: "192.168.1.1", now: now)
    eq(down.level, .crit, "диагностика: роутер недоступен — критично")
    check(!down.metrics.routerReachable, "диагностика: недоступный роутер не помечен доступным")
}
func Field_uptime(_ status: [String: Any]) -> Int { ((status["DeviceInfo"] as? [String: Any])?["UpTime"] as? Int) ?? 0 }

let entry = parseLog(["[attack] Dec 31 23:00:00 kern.alert kernel: UDP PORT SCAN ATTACK:IN=ppp2"]).first!
let jan = DateComponents(calendar: Calendar.current, year: 2027, month: 1, day: 1, hour: 1).date!
eq(logDate(entry, now: jan).map { Calendar.current.component(.year, from: $0) }, 2026, "журнал: запись 31 дек в январе относится к прошлому году")


// MARK: обрывы связи (те же случаи, что в android/.../ConnectionDropsTest.kt)
func loadFixtureAny(_ name: String) -> Any? {
    let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    guard let data = try? Data(contentsOf: root.appendingPathComponent("fixtures/real/\(name).json")) else { return nil }
    return try? JSONSerialization.jsonObject(with: data)
}
var prague = Calendar(identifier: .gregorian)
prague.timeZone = TimeZone(identifier: "Europe/Prague")!
func at(_ y: Int, _ mo: Int, _ d: Int, _ h: Int, _ mi: Int, _ s: Int = 0) -> Int {
    Int(prague.date(from: DateComponents(year: y, month: mo, day: d, hour: h, minute: mi, second: s))!.timeIntervalSince1970)
}
func lost(_ time: String, _ iface: String = "VDSL") -> String { "[system] \(time) user.notice zcmdModuleCfg: System: Wan interface \(iface) connection lost" }
func ready(_ time: String, _ iface: String = "VDSL") -> String { "[system] \(time) user.notice zcmdModuleCfg: System: Wan interface \(iface) connection ready" }
func drops(_ lines: [String], _ now: Int) -> [ConnectionDrop] { dropsFromLog(parseLog(lines), now: now, calendar: prague) }

// Настоящий журнал и статистика линии: сняты 22.11.2026 в 19:15, роутер работает 14 суток, сессия PPP — 7104 с.
let captured = at(2026, 11, 22, 19, 15, 20)
if let rawLog = loadFixtureAny("syslog_drops"), let realLine = parseLineStats(lineStatsText(loadFixtureAny("xdsl_statistics")), now: captured) {
    let realLog = parseLog(rawLog)
    check(realLog.count > 500, "обрывы: настоящий журнал разобран (\(realLog.count))")
    let found = dropsFromLog(realLog, now: captured, calendar: prague)
    eq(found.count, 1, "обрывы: в настоящем журнале один инцидент")
    if let d = found.first {
        eq(d.lostAt, at(2026, 11, 22, 17, 14, 58), "обрывы: начало — конец сессии pppd")
        eq(d.restoredAt, at(2026, 11, 22, 17, 16, 58), "обрывы: конец — последнее «connection ready»")
        eq(d.durationSec, 120, "обрывы: длительность 2 минуты")
        eq(d.flaps, 3, "обрывы: три потери связи подряд")
        eq(d.kind, .pppSession, "обрывы: оборвалась сессия PPP")
        eq(d.iface, "VDSL", "обрывы: интерфейс из журнала")
        eq(d.sessionMinutes, 20355, "обрывы: длина оборвавшейся сессии")
        check(d.cause == nil, "обрывы: причину роутер не записал")
        check(d.lines.first?.contains("Connect time 20355.0 minutes") == true, "обрывы: итог сессии pppd стоит первым")
        eq(d.lines.filter { $0.contains("connection lost") }.count, 2, "обрывы: обе строки «connection lost» в контексте")
        check(!d.lines.contains { $0.contains("ZTR69") || $0.contains("dnsmasq") }, "обрывы: посторонних строк в контексте нет")
    }
    eq(realLine.downKbps, 184_364, "линия: скорость приёма"); eq(realLine.upKbps, 55_613, "линия: скорость отдачи")
    eq(realLine.snrDown, 6.3, "линия: запас по шуму ↓"); eq(realLine.snrUp, 7.5, "линия: запас по шуму ↑")
    eq(realLine.attnDown, 9.7, "линия: затухание ↓"); eq(realLine.powerDown, 12.3, "линия: мощность ↓")
    eq(realLine.crcDown, 45, "линия: ошибки CRC"); eq(realLine.fecDown, 17_390_643, "линия: FEC ↓"); eq(realLine.fecUp, 3449, "линия: FEC ↑")
    eq(realLine.uptimeSec, 14 * 86400 + 5 * 3600 + 15 * 60 + 21, "линия: «14 days,  5:15, 21 secs»")
    check(realLine.up && realLine.modulation.contains("G.993.5"), "линия: состояние и модуляция")
    eq(parseLineUptime("1 min, 10 secs"), 70, "линия: «1 min, 10 secs»")
    check(parseLineStats("ZCFG_NO_SUCH_OBJECT", now: captured) == nil, "линия: не статистика DSL — nil")

    func realLink(_ later: Int = 0) -> LinkStatus {
        var line = realLine; line.capturedAt = captured + later; line.uptimeSec = realLine.uptimeSec! + later
        return LinkStatus(routerUptime: 1_228_689 + later, wanUp: true, wanUptime: 7104 + later, pppoe: true, line: line)
    }
    let first = DropLog().record(realLog, link: realLink(), now: captured, calendar: prague)
    eq(first.drops.count, 1, "обрывы: время работы PPP совпало с концом инцидента — лишнего нет")
    eq(first.drops.first?.lineHeld, true, "обрывы: линия DSL держала синхронизацию")
    eq(first.drops.first?.kind, .pppSession, "обрывы: вид не меняется на обрыв линии")
    eq(first.drops.first?.line?.downKbps, 184_364, "обрывы: замер линии приложен")
    let again = first.record(realLog, link: realLink(300), now: captured + 300, calendar: prague)
    check(again.drops == first.drops, "обрывы: повторное чтение не меняет историю")
    let tail = realLog.filter { (logEpoch($0, now: captured, calendar: prague) ?? 0) >= at(2026, 11, 22, 17, 16, 40) }
    eq(dropsFromLog(tail, now: captured, calendar: prague).first?.lostAt, at(2026, 11, 22, 17, 16, 46), "обрывы: в хвосте журнала свой короткий инцидент")
    let later = again.record(tail, link: realLink(3600), now: captured + 3600, calendar: prague)
    check(later.drops == first.drops, "обрывы: хвост журнала сливается с сохранённым инцидентом")
    check(later.added(since: first).isEmpty && first.added(since: DropLog()).count == 1, "обрывы: новым считается только впервые найденный")
    let coded = try! JSONDecoder().decode(DropLog.self, from: JSONEncoder().encode(first))
    check(coded == first, "обрывы: история переживает запись в JSON")
    // Формат JSON общий с Android: имена полей и коды перечислений.
    let json = String(data: try! JSONEncoder().encode(first), encoding: .utf8)!
    check(json.contains("\"kind\":\"PPP_SESSION\"") && json.contains("\"lostAt\":\(at(2026, 11, 22, 17, 14, 58))") && json.contains("\"lastLine\""), "обрывы: формат JSON как в Android")

    // Отчёт диагностики: итог и раздел с таблицей.
    var report = DiagReport(date: Date(timeIntervalSince1970: TimeInterval(captured)))
    evaluateDrops(&report, log: realLog, link: realLink(), now: report.date, calendar: prague)
    eq(report.newDrops.count, 1, "отчёт: новый обрыв найден")
    check(report.findings.contains { $0.area == "Обрывы связи" && $0.level == .warn && $0.text.contains("обрывов связи: 1") && $0.text.contains("17:14:58") && $0.text.contains("2 мин 0 с") }, "отчёт: итог называет число и время обрыва")
    let md = renderMarkdown(report)
    check(md.contains("## Обрывы связи") && md.contains("| Время | Длительность |") && md.contains("оборвалась сессия PPP") && md.contains("| 7 дней | 1 | 3 | 2 мин 0 с |"), "отчёт: раздел «Обрывы связи» с таблицей и сводкой")
    check(md.contains("Wan interface VDSL connection lost"), "отчёт: строки журнала последнего обрыва")
    eq(report.findings.filter { $0.text.contains("бры") }.count, 1, "отчёт: обрыв не дублируется другими замечаниями")
} else {
    check(false, "обрывы: фикстуры syslog_drops и xdsl_statistics не найдены")
}

do {
    let now = at(2026, 11, 22, 20, 0)
    let two = drops([lost("Nov 22 17:16:08"), ready("Nov 22 17:16:36"), lost("Nov 22 17:16:46"), ready("Nov 22 17:16:58"), lost("Nov 22 17:30:00"), ready("Nov 22 17:30:38")], now)
    eq(two.map(\.flaps), [2, 1], "обрывы: потери в пределах 5 минут — один инцидент")
    eq(two.map(\.durationSec), [50, 38], "обрывы: длительности инцидентов")
    eq(drops([lost("Nov 22 17:16:08"), lost("Nov 22 17:16:20", "WWAN"), ready("Nov 22 17:16:40", "WWAN")], now).count, 2, "обрывы: другой интерфейс — другой инцидент")
    eq(drops([lost("Nov 22 10:00:00"), ready("Nov 22 12:30:00")], now).first?.durationSec, 9000, "обрывы: долгий обрыв заканчивается своим восстановлением")
    let lostTwice = drops([lost("Nov 22 10:00:00"), lost("Nov 22 18:00:00"), ready("Nov 22 18:00:30")], now)
    check(lostTwice.count == 2 && lostTwice[0].restoredAt == nil && lostTwice[1].durationSec == 30, "обрывы: новая потеря после долгой тишины — новый инцидент")

    let newYear = drops([lost("Dec 31 23:59:50"), ready("Jan  1 00:00:20")], at(2027, 1, 2, 9, 0)).first
    eq(newYear?.lostAt, at(2026, 12, 31, 23, 59, 50), "обрывы: 31 декабря в январе — прошлый год")
    eq(newYear?.durationSec, 30, "обрывы: инцидент через Новый год")

    let onlyLost = parseLog([lost("Nov 22 19:00:00")])
    check(DropLog().record(onlyLost, link: LinkStatus(routerUptime: 500_000, wanUp: false, wanUptime: 0, pppoe: true, line: nil), now: now, calendar: prague).drops.first?.restoredAt == nil, "обрывы: связи нет — восстановления нет")
    let up = DropLog().record(onlyLost, link: LinkStatus(routerUptime: 500_000, wanUp: true, wanUptime: 3540, pppoe: true, line: nil), now: now, calendar: prague)
    check(up.drops.first?.restoredAt == at(2026, 11, 22, 19, 1) && up.drops.first?.restoredInferred == true, "обрывы: восстановление выведено из времени работы WAN")
    let exact = up.record(parseLog([lost("Nov 22 19:00:00"), ready("Nov 22 19:00:48")]), link: nil, now: now, calendar: prague).drops.first
    check(exact?.durationSec == 48 && exact?.restoredInferred == false, "обрывы: восстановление из журнала важнее вычисленного")

    let link = LinkStatus(routerUptime: 500_000, wanUp: true, wanUptime: 3600, pppoe: true, line: nil)
    let inferred = DropLog().record([], link: link, now: now, calendar: prague)
    check(inferred.drops.count == 1 && inferred.drops[0].lostAt == nil && inferred.drops[0].restoredAt == now - 3600 && inferred.drops[0].kind == .pppSession, "обрывы: переподключение, которого нет в журнале")
    check(inferred.record([], link: LinkStatus(routerUptime: 500_300, wanUp: true, wanUptime: 3897, pppoe: true, line: nil), now: now + 300, calendar: prague).drops == inferred.drops, "обрывы: выведенный инцидент не дублируется")
    check(DropLog().record([], link: LinkStatus(routerUptime: 4000, wanUp: true, wanUptime: 3800, pppoe: true, line: nil), now: now, calendar: prague).drops.isEmpty, "обрывы: подключение после загрузки роутера — не обрыв")

    var line = LineStats(capturedAt: now, up: true, modulation: "", downKbps: 100_000, upKbps: 20_000, crcDown: 45, crcUp: 0, fecDown: 0, fecUp: 0, uptimeSec: 3600)
    let resync = DropLog().record(parseLog([lost("Nov 22 18:58:20"), ready("Nov 22 19:00:40"), lost("Nov 22 19:40:00"), ready("Nov 22 19:40:20")]),
                                  link: LinkStatus(routerUptime: 500_000, wanUp: true, wanUptime: 1180, pppoe: true, line: line), now: now, calendar: prague).drops
    check(resync.count == 2 && resync[0].kind == .dslLine && resync[0].lineHeld == false && resync[1].kind == .wan && resync[1].lineHeld == true, "обрывы: пересинхронизация линии делает обрыв обрывом DSL")
    let lineOnly = DropLog().record([], link: LinkStatus(routerUptime: 500_000, wanUp: true, wanUptime: 3570, pppoe: true, line: line), now: now, calendar: prague).drops
    check(lineOnly.count == 1 && lineOnly[0].kind == .dslLine && lineOnly[0].restoredAt == now - 3600, "обрывы: обрыв линии выведен из времени её работы")

    line.uptimeSec = 1_228_521
    var before = line; before.capturedAt = now - 1800; before.crcDown = 40
    let crc = DropLog(lastLine: before).record(parseLog([lost("Nov 22 19:50:00"), ready("Nov 22 19:50:30")]),
                                               link: LinkStatus(routerUptime: 1_228_689, wanUp: true, wanUptime: 570, pppoe: true, line: line), now: now, calendar: prague).drops.first
    check(crc?.crcDelta == 5 && crc?.crcSince == now - 1800, "обрывы: прирост ошибок CRC с прошлого замера")

    let ppp = drops([
        "[pppoe] Nov 22 19:00:00 daemon.info pppd: No response to 5 echo-requests",
        "[pppoe] Nov 22 19:00:00 daemon.notice pppd: Serial link appears to be disconnected.",
        "[pppoe] Nov 22 19:00:01 daemon.notice pppd: Json PPP { \"linkname\": \"pppd3\", \"type\": \"ipv4\", \"state\": \"down\" }",
        "[pppoe] Nov 22 19:01:05 daemon.warning pppd: Timeout waiting for PADO packets",
        "[pppoe] Nov 22 19:02:10 daemon.notice pppd: Json PPP { \"type\": \"ipv4\", \"state\": \"up\" }",
    ], now).first
    check(ppp?.cause == .noEcho && ppp?.causeText == "No response to 5 echo-requests" && ppp?.flaps == 1 && ppp?.durationSec == 130 && ppp?.kind == .pppSession, "обрывы: причина из сообщений pppd")
    let other = parseLog([
        "[kernel] Nov 22 19:00:00 kern.err kernel: DSL line retrained",
        "[ppp] Nov 22 19:00:00 daemon.info pppd: PPP session established",
        "[tr69] Nov 22 19:00:00 daemon.info ZTR69: [ERROR ] cwmp_monitor_cwmp(): wan not ready",
        "[dhcpc] Nov 22 19:00:00 user.err dhcp6c: client6_send: transmit failed: Network is unreachable",
        "[system] Nov 22 19:00:00 user.notice zcmdModuleCfg: System: configuration saved",
        ready("Nov 22 19:05:00"),
    ])
    check(dropsFromLog(other, now: now, calendar: prague).isEmpty, "обрывы: незнакомые формулировки не считаются обрывом")
    check(other.map(dropLineIsLoss) == [nil, nil, nil, nil, nil, false], "обрывы: подсветка только строк о связи")
    eq(drops(["[xdsl] Nov 22 19:00:00 daemon.notice xdsl: DSL link down", "[xdsl] Nov 22 19:01:00 daemon.notice xdsl: DSL link up"], now).first?.kind, .dslLine, "обрывы: категория xdsl — обрыв линии")

    let old = ConnectionDrop(lostAt: now - 100 * 86400, restoredAt: now - 100 * 86400 + 30)
    let recent = ConnectionDrop(lostAt: now - 89 * 86400, restoredAt: now - 89 * 86400 + 30)
    check(DropLog(drops: [old, recent]).record([], link: nil, now: now, calendar: prague).drops == [recent], "обрывы: история хранится 90 дней")
    let many = (1...(dropMaxStored + 20)).map { ConnectionDrop(lostAt: now - $0 * 3600, restoredAt: now - $0 * 3600 + 10) }
    let kept = DropLog(drops: many).record([], link: nil, now: now, calendar: prague).drops
    check(kept.count == dropMaxStored && kept.last?.lostAt == now - 3600, "обрывы: предел числа записей, остаются новые")
    eq(dropsWithin(kept, now: now, seconds: 86400), 24, "обрывы: счёт за сутки")
    eq(formatShortDuration(38), "38 с", "обрывы: короткая длительность"); eq(formatShortDuration(120), "2 мин 0 с", "обрывы: длительность в минутах")
}

print("\n==== \(failures == 0 ? "ВСЕ ТЕСТЫ ПРОЙДЕНЫ" : "ПРОВАЛЕНО: \(failures)") ====")
exit(failures == 0 ? 0 : 1)
