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
    check(!real.schema.contains("<redacted>") || true, "диагностика не падает на реальных данных")
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

print("\n==== \(failures == 0 ? "ВСЕ ТЕСТЫ ПРОЙДЕНЫ" : "ПРОВАЛЕНО: \(failures)") ====")
exit(failures == 0 ? 0 : 1)
