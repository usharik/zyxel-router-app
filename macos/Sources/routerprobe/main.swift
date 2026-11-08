import Foundation
import RouterKit

// Checks the protocol from the command line. Without ROUTER_PASSWORD only the public endpoints are touched.
let address = CommandLine.arguments.dropFirst().first ?? "192.168.1.1"
let client = try RouterClient(address: address)
let basic = try await client.basicInfo()
print("basic:", basic.model, basic.firmware)
print("rsa key usable:", try await client.publicKeyLooksValid())
guard let password = ProcessInfo.processInfo.environment["ROUTER_PASSWORD"] else { exit(0) }
do {
    try await client.login(username: ProcessInfo.processInfo.environment["ROUTER_USER"] ?? "admin", password: password)
} catch { print("login error:", error.localizedDescription); exit(2) }
let s = try await client.snapshot()
print("model:", s.model, "uptime:", s.uptime, "internet:", s.internetUp, s.linkType, s.encapsulation, s.wanIP, s.dns, s.internetUptime, "dsl:", s.downKbps, s.upKbps)
for d in s.devices { print("device:", d.name, d.ip, d.mac, d.connection, "net=", d.network, d.online, d.guest) }
for w in s.wifi { print("wifi:", w.ssid, w.band, w.enabled, w.channel, w.security, w.guest) }
print("schema has values:", s.schema.contains("MUST_NOT_APPEAR") || s.schema.contains("203.0.113.7"))
let pc = try await client.parentalControl()
print("parental: enabled=\(pc.enabled) max=\(pc.maxProfiles) profiles=\(pc.profiles.count)")
for p in pc.profiles { print("  profile:", p.index, p.name, "on=\(p.enabled)", "macs=\(p.macs.count)", "days=\(p.days.joined(separator: "/"))", "times=\(p.times.joined(separator: ","))", "sites=\(p.blocksSites)") }
if ProcessInfo.processInfo.environment["ROUTER_WRITE"] == "1", let first = pc.profiles.first {
    try await client.setParentalEnabled(!pc.enabled)
    try await client.setProfileEnabled(index: first.index, on: !first.enabled)
    let pc2 = try await client.parentalControl()
    print("after write: enabled=\(pc2.enabled) profile0 on=\(pc2.profiles.first?.enabled ?? false)")
}
if ProcessInfo.processInfo.environment["ROUTER_CRUD"] == "1" {
    try await client.saveProfile(index: nil, name: "Тест", macs: ["14:33:75:00:00:01"], days: ["Mon", "Wed"], timeRangeList: "18:00-20:00", enabled: true)
    var pc = try await client.parentalControl()
    print("after create: count=\(pc.profiles.count) last=\(pc.profiles.last?.name ?? "?") days=\(pc.profiles.last?.days.joined(separator: "/") ?? "")")
    if let created = pc.profiles.last {
        try await client.saveProfile(index: created.index, name: "Тест2", macs: ["14:33:75:00:00:01"], days: ["Fri"], timeRangeList: "19:00-21:00", enabled: false)
        pc = try await client.parentalControl()
        print("after edit: last=\(pc.profiles.last?.name ?? "?") on=\(pc.profiles.last?.enabled ?? true) days=\(pc.profiles.last?.days.joined(separator: "/") ?? "")")
        try await client.deleteProfile(index: created.index)
        pc = try await client.parentalControl()
        print("after delete: count=\(pc.profiles.count)")
    }
}
if ProcessInfo.processInfo.environment["ROUTER_MERGE"] == "1" {
    // Редактируем существующий профиль «Дети» (с URLFilter) только по имени — фильтр сайтов должен сохраниться.
    let before = try await client.parentalControl()
    if let p = before.profiles.first(where: { $0.blocksSites }) {
        print("merge before: \(p.name) blocksSites=\(p.blocksSites) days=\(p.days.joined(separator: "/"))")
        try await client.saveProfile(index: p.index, name: p.name + "*", macs: p.macs.map { $0.replacingOccurrences(of: "-", with: ":") }, days: p.days, timeRangeList: p.times.joined(separator: ","), enabled: p.enabled)
        let after = try await client.parentalControl()
        if let p2 = after.profiles.first(where: { $0.index == p.index }) {
            print("merge after:  \(p2.name) blocksSites=\(p2.blocksSites) days=\(p2.days.joined(separator: "/"))  (должно остаться true)")
        }
    } else {
        print("merge: профиль с фильтром сайтов не найден")
    }
}
let r = try await client.routing()
print("routing: static=\(r.staticRoutes.count) dns=\(r.dnsRoutes.count) policy=\(r.policyRoutes.count) rip=\(r.rip.count)")
for s in r.staticRoutes { print("  static:", s.title, "|", s.subtitle, s.enabled) }
for s in r.policyRoutes { print("  policy:", s.title, "|", s.subtitle) }
for s in r.rip { print("  rip:", s.title, "|", s.subtitle) }
if ProcessInfo.processInfo.environment["ROUTER_REBOOT"] == "1" { try await client.reboot(); print("reboot ok") }
await client.logout()
print("logout ok")
