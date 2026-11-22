import Foundation

// Обрывы связи с провайдером: чистая логика без сети. Из записей журнала роутера (и, если есть, состояния WAN и
// линии DSL) собираются инциденты ConnectionDrop; они копятся локально, потому что журнал роутера короткий
// (на VMG8623-T50B ~600 строк, это несколько часов).
//
// Та же логика на Kotlin — android/app/src/main/kotlin/com/usharik/routermanager/ConnectionDrops.kt. Правила,
// константы и формат JSON должны совпадать: меняя одно, меняйте и другое (тесты на общей фикстуре
// fixtures/real/syslog_drops.json есть с обеих сторон).

/// Что оборвалось. Порядок — от общего к точному: при слиянии двух записей об одном инциденте берётся более точное.
public enum DropKind: String, Codable, Comparable, Sendable {
    case wan = "WAN", pppSession = "PPP_SESSION", dslLine = "DSL_LINE"
    private var rank: Int { self == .wan ? 0 : self == .pppSession ? 1 : 2 }
    public static func < (a: DropKind, b: DropKind) -> Bool { a.rank < b.rank }
    public var title: String {
        switch self {
        case .wan: return "пропало подключение к интернету"
        case .pppSession: return "оборвалась сессия PPP"
        case .dslLine: return "линия DSL потеряла синхронизацию"
        }
    }
}

/// Причина, если роутер её записал (сообщения pppd).
public enum DropCause: String, Codable, Sendable {
    case peerTerminated = "PEER_TERMINATED", noEcho = "NO_ECHO", hangup = "HANGUP", noServer = "NO_SERVER", authFailed = "AUTH_FAILED"
    public var title: String {
        switch self {
        case .peerTerminated: return "провайдер закрыл сессию"
        case .noEcho: return "провайдер перестал отвечать (нет ответа на LCP echo)"
        case .hangup: return "линия разъединилась"
        case .noServer: return "сервер PPPoE провайдера не отвечал"
        case .authFailed: return "провайдер отклонил логин или пароль"
        }
    }
}

public enum LinkRole: Sendable { case down, up, cause, info }
public enum LinkSource: Sendable { case system, ppp, dsl }
public struct LinkSignal: Sendable {
    public let role: LinkRole
    public let source: LinkSource
    public var iface = ""
    public var cause: DropCause?
    public var sessionMinutes: Int?
}

/// События ближе этого интервала (секунды) — один инцидент: связь «моргает» несколько раз подряд.
public let dropGroupGap = 300
/// Соединение, поднятое в первые минуты после загрузки роутера, — обычный старт, а не обрыв.
public let dropBootGrace = 600
/// Допуск при сравнении времени из журнала (часы роутера) и времени работы соединения (часы компьютера).
public let dropClockTolerance = 60
public let dropRetentionDays = 90
public let dropMaxStored = 500
public let dropMaxLines = 40
/// Замер линии позже этого срока после обрыва уже ничего не говорит о нём.
public let dropLineStatsMaxAge = 6 * 3600

private func matches(_ text: String, _ pattern: String) -> Bool {
    text.range(of: pattern, options: [.regularExpression, .caseInsensitive]) != nil
}

// ---- Правила распознавания. Все формулировки прошивки — только здесь. ----
// Подтверждено на VMG8623-T50B V5.50(ABPM.7.3): «Wan interface … connection lost/ready» и сообщения pppd
// «Json PPP … state», «Connect time», «Sent PADT» и др. Причины pppd (LCP terminated, echo-requests, Modem hangup,
// PADO) — штатные тексты pppd/rp-pppoe; на живом роутере не встречались. Категория xdsl на живом роутере пуста
// (линия не падала), поэтому её правило — осторожное предположение.
private let pppCauses: [(String, DropCause)] = [
    ("LCP terminated by peer", .peerTerminated),
    (#"No response to \d+ echo-requests|Serial link appears to be disconnected"#, .noEcho),
    ("Modem hangup", .hangup),
    ("Timeout waiting for PAD[OS] packets|Unable to complete PPPoE Discovery", .noServer),
    ("authentication failed", .authFailed),
]
private let pppInfo = #"^(Sent \d+ bytes|Sent PADT|PPP session is|Using interface|Connect: |Connection terminated|Remote message|(PAP|CHAP) authentication succeeded|peer from calling number|(local|remote) +(IP|LL) address|(primary|secondary) +DNS address)"#
private let dslDown = #"\b(link|line)\b.*\b(down|lost)\b|\bloss of (signal|sync|frame)|\bretrain"#
private let dslUp = #"\b(link|line)\b.*\bup\b|\bshowtime\b"#

/// Что строка журнала говорит о связи с провайдером; nil — к обрывам не относится.
public func classifyLinkEvent(_ e: LogEntry) -> LinkSignal? {
    let m = e.message
    if let w = m.firstMatch(of: #/wan interface (\S+) connection (lost|ready)/#.ignoresCase()) {
        return LinkSignal(role: w.2.lowercased() == "lost" ? .down : .up, source: .system, iface: String(w.1))
    }
    if e.process.lowercased().hasPrefix("pppd") {
        if let s = m.firstMatch(of: #/"state"\s*:\s*"(up|down)"/#.ignoresCase()) {
            return LinkSignal(role: s.1.lowercased() == "down" ? .down : .up, source: .ppp)
        }
        if let c = pppCauses.first(where: { matches(m, $0.0) }) { return LinkSignal(role: .cause, source: .ppp, cause: c.1) }
        if let t = m.firstMatch(of: #/^Connect time ([\d.]+) minutes/#.ignoresCase()) {
            return LinkSignal(role: .info, source: .ppp, sessionMinutes: Double(t.1).map { Int($0) })
        }
        return matches(m, pppInfo) ? LinkSignal(role: .info, source: .ppp) : nil
    }
    if e.category.lowercased() == "xdsl" {
        if matches(m, dslDown) { return LinkSignal(role: .down, source: .dsl) }
        if matches(m, dslUp) { return LinkSignal(role: .up, source: .dsl) }
    }
    return nil
}

/// Строка журнала о потере связи (true), о восстановлении (false) или не про это (nil) — для подсветки в списке.
public func dropLineIsLoss(_ e: LogEntry) -> Bool? {
    switch classifyLinkEvent(e)?.role {
    case .down, .cause: return true
    case .up: return false
    default: return nil
    }
}

/// Время записи журнала (секунды эпохи); правило года — в `logDate`.
public func logEpoch(_ e: LogEntry, now: Int, calendar: Calendar = .current) -> Int? {
    guard e.month >= 1, e.month <= 12, e.day >= 1, e.day <= 31, e.time.split(separator: ":").allSatisfy({ Int($0) != nil }) else { return nil }
    return logDate(e, now: Date(timeIntervalSince1970: TimeInterval(now)), calendar: calendar).map { Int($0.timeIntervalSince1970) }
}

/// Состояние линии DSL из «xDSL Statistics» роутера на момент `capturedAt`. Счётчики ошибок — с начала синхронизации.
public struct LineStats: Codable, Equatable, Sendable {
    public var capturedAt: Int
    public var up: Bool
    public var modulation: String
    public var downKbps: Int, upKbps: Int
    public var snrDown: Double?, snrUp: Double?, attnDown: Double?, attnUp: Double?, powerDown: Double?, powerUp: Double?
    public var crcDown: Int, crcUp: Int, fecDown: Int, fecUp: Int
    /// Сколько линия держит синхронизацию; nil — прошивка не сообщила.
    public var uptimeSec: Int?

    public init(capturedAt: Int, up: Bool, modulation: String, downKbps: Int, upKbps: Int, snrDown: Double? = nil, snrUp: Double? = nil,
                attnDown: Double? = nil, attnUp: Double? = nil, powerDown: Double? = nil, powerUp: Double? = nil,
                crcDown: Int, crcUp: Int, fecDown: Int, fecUp: Int, uptimeSec: Int?) {
        self.capturedAt = capturedAt; self.up = up; self.modulation = modulation; self.downKbps = downKbps; self.upKbps = upKbps
        self.snrDown = snrDown; self.snrUp = snrUp; self.attnDown = attnDown; self.attnUp = attnUp; self.powerDown = powerDown; self.powerUp = powerUp
        self.crcDown = crcDown; self.crcUp = crcUp; self.fecDown = fecDown; self.fecUp = fecUp; self.uptimeSec = uptimeSec
    }
}

/// Текст статистики из ответа `/cgi-bin/xDSLStatistics_handle?line=0`: `[{"result": "…"}]`.
public func lineStatsText(_ reply: Any?) -> String? {
    guard let text = ((reply as? [Any])?.first as? [String: Any])?["result"] as? String,
          !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
    return text
}

/// «14 days,  5:15, 21 secs», «1 min, 10 secs», «3 hours, 2 min, 5 secs» → секунды.
public func parseLineUptime(_ text: String) -> Int? {
    let days = text.firstMatch(of: #/(\d+)\s*days?/#.ignoresCase()).flatMap { Int($0.1) }
    let hm = text.firstMatch(of: #/(\d+):(\d+)/#).map { (Int($0.1) ?? 0, Int($0.2) ?? 0) }
    let hours = text.firstMatch(of: #/(\d+)\s*hours?/#.ignoresCase()).flatMap { Int($0.1) }
    let min = text.firstMatch(of: #/(\d+)\s*min/#.ignoresCase()).flatMap { Int($0.1) }
    let sec = text.firstMatch(of: #/(\d+)\s*secs?/#.ignoresCase()).flatMap { Int($0.1) }
    if days == nil, hm == nil, hours == nil, min == nil, sec == nil { return nil }
    return (days ?? 0) * 86400 + (hm?.0 ?? 0) * 3600 + (hm?.1 ?? 0) * 60 + (hours ?? 0) * 3600 + (min ?? 0) * 60 + (sec ?? 0)
}

/// Разбирает текст «xDSL Statistics». near-end — приём на роутере (downstream), far-end — приём на стороне
/// провайдера (upstream). nil, если это не статистика DSL (другая модель, Ethernet WAN).
public func parseLineStats(_ text: String?, now: Int) -> LineStats? {
    guard let text, text.range(of: "Line State", options: .caseInsensitive) != nil else { return nil }
    var values: [String: String] = [:]
    for line in text.split(whereSeparator: \.isNewline) {
        guard let colon = line.firstIndex(of: ":") else { continue }
        let key = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
        if values[key] == nil { values[key] = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces) }
    }
    func value(_ label: String) -> String? { values[label.lowercased()] }
    func num(_ label: String) -> Double? { value(label).flatMap { $0.firstMatch(of: #/-?\d+(\.\d+)?/#) }.flatMap { Double($0.0) } }
    func both(_ prefix: String, _ what: String) -> Int { ["fast", "interleaved"].reduce(0) { $0 + Int(num("\(prefix) \(what) \($1)") ?? 0) } }
    func rate(_ side: String) -> Int { ["fast", "interleaved"].reduce(0) { $0 + Int(num("\(side) \($1) channel bit rate") ?? 0) } }
    return LineStats(
        capturedAt: now, up: value("Line State")?.lowercased() == "up", modulation: value("Modulation") ?? "",
        downKbps: rate("near-end"), upKbps: rate("far-end"),
        snrDown: num("noise margin downstream"), snrUp: num("noise margin upstream"),
        attnDown: num("attenuation downstream"), attnUp: num("attenuation upstream"),
        powerDown: num("output power downstream"), powerUp: num("output power upstream"),
        crcDown: both("near-end", "CRC error"), crcUp: both("far-end", "CRC error"),
        fecDown: both("near-end", "FEC error"), fecUp: both("far-end", "FEC error"),
        uptimeSec: value("DSL uptime").flatMap(parseLineUptime))
}

/// Один инцидент: связь пропала и вернулась (возможно, несколько раз подряд — `flaps`).
/// Хотя бы одно из `lostAt` и `restoredAt` известно.
public struct ConnectionDrop: Codable, Equatable, Sendable, Identifiable {
    /// Когда связь пропала; nil — начало в журнал не попало (инцидент выведен из времени работы соединения).
    public var lostAt: Int?
    /// Когда связь вернулась; nil — ещё не вернулась или восстановление не видно.
    public var restoredAt: Int?
    /// `restoredAt` вычислено из времени работы соединения, а не взято из журнала.
    public var restoredInferred = false
    public var kind = DropKind.wan
    /// Имя WAN-интерфейса из журнала («VDSL», «ADSL», «ETHWAN»); пусто, если роутер его не назвал.
    public var iface = ""
    /// Сколько раз связь пропадала за инцидент.
    public var flaps = 1
    public var cause: DropCause?
    /// Строка журнала с причиной как есть.
    public var causeText = ""
    /// Сколько минут прожила оборвавшаяся PPP-сессия (pppd «Connect time»).
    public var sessionMinutes: Int?
    /// Линия DSL при этом держала синхронизацию (true), пересинхронизировалась (false) или неизвестно (nil).
    public var lineHeld: Bool?
    /// Строки журнала роутера об этом инциденте, как есть.
    public var lines: [String] = []
    /// Линия при первом чтении после обрыва.
    public var line: LineStats?
    /// Прирост ошибок CRC на приёме между замером до обрыва (`crcSince`) и замером после.
    public var crcDelta: Int?
    public var crcSince: Int?

    public init(lostAt: Int?, restoredAt: Int?, restoredInferred: Bool = false, kind: DropKind = .wan, lineHeld: Bool? = nil) {
        self.lostAt = lostAt; self.restoredAt = restoredAt; self.restoredInferred = restoredInferred; self.kind = kind; self.lineHeld = lineHeld
    }

    /// Время инцидента для сортировки и как ключ: после слияния у разных инцидентов оно разное.
    public var at: Int { lostAt ?? restoredAt ?? 0 }
    public var id: Int { at }
    public var durationSec: Int? { if let lostAt, let restoredAt { return max(0, restoredAt - lostAt) } else { return nil } }
    var end: Int { restoredAt ?? at }
}

/// Собирает инциденты из журнала (записи идут от старых к новым).
public func dropsFromLog(_ entries: [LogEntry], now: Int, calendar: Calendar = .current) -> [ConnectionDrop] {
    struct Builder {
        let start: Int
        var last: Int
        var down = false, flaps = 0, lastUp: Int?, iface = ""
        var cause: DropCause?, causeText = "", sessionMinutes: Int?
        var sources: Set<LinkSource> = [], lines: [String] = []
        init(_ start: Int) { self.start = start; last = start }

        mutating func add(_ s: LinkSignal, _ t: Int, _ e: LogEntry) {
            last = max(last, t)
            if iface.isEmpty { iface = s.iface }
            if lines.count < dropMaxLines { lines.append(e.raw) }
            switch s.role {
            case .down, .cause:
                if !down { flaps += 1; down = true }
                sources.insert(s.source)
                if s.role == .cause, cause == nil { cause = s.cause; causeText = e.message }
            case .up: down = false; lastUp = t
            case .info: if sessionMinutes == nil { sessionMinutes = s.sessionMinutes }
            }
        }

        func build() -> ConnectionDrop {
            var d = ConnectionDrop(lostAt: start, restoredAt: down ? nil : lastUp,
                                   kind: sources.contains(.dsl) ? .dslLine : sources.contains(.ppp) ? .pppSession : .wan)
            d.iface = iface; d.flaps = flaps; d.cause = cause; d.causeText = causeText; d.sessionMinutes = sessionMinutes; d.lines = lines
            return d
        }
    }

    var out: [ConnectionDrop] = []
    var current: Builder?
    // pppd пишет итог сессии («Connect time…») за мгновение до «state: down» — эти строки ждут здесь начала инцидента.
    var pending: [(LinkSignal, Int, LogEntry)] = []
    for e in entries {
        guard let s = classifyLinkEvent(e), let t = logEpoch(e, now: now, calendar: calendar) else { continue }
        if let b = current {
            let gap = t - b.last
            let otherIface = !s.iface.isEmpty && !b.iface.isEmpty && s.iface.lowercased() != b.iface.lowercased()
            // Пока связи нет, её восстановление относится к этому инциденту, сколько бы ни прошло. Новая потеря
            // после долгой тишины — уже другой инцидент (восстановление предыдущего в журнал не попало).
            let joins = gap >= 0 && !otherIface && (gap <= dropGroupGap || (b.down && (s.role == .up || s.role == .info)))
            if !joins { out.append(b.build()); current = nil }
        }
        if current == nil {
            if s.role == .up || s.role == .info {
                pending = (pending + [(s, t, e)]).filter { (0...5).contains(t - $0.1) }
                continue
            }
            var b = Builder(t)
            for p in pending where (0...5).contains(t - p.1) && p.0.role == .info { b.add(p.0, t, p.2) }
            current = b
        }
        pending = []
        current?.add(s, t, e)
    }
    if let b = current { out.append(b.build()) }
    return out
}

/// Сливает сохранённые инциденты с только что прочитанными. Один и тот же журнал читается много раз, а его начало
/// уходит из буфера роутера, поэтому тождество инцидента — пересечение по времени (с допуском `dropGroupGap`),
/// а не совпадение полей: запись с более ранним началом дополняется более поздними сведениями.
public func mergeDrops(_ stored: [ConnectionDrop], _ fresh: [ConnectionDrop]) -> [ConnectionDrop] {
    var out: [ConnectionDrop] = []
    // Сортировка устойчивая по построению: при равном времени сохранённая запись идёт раньше прочитанной.
    for (_, d) in (stored + fresh).enumerated().sorted(by: { ($0.element.at, $0.offset) < ($1.element.at, $1.offset) }) {
        guard let a = out.last, d.at <= a.end + dropGroupGap else { out.append(d); continue }
        var m = a
        m.lostAt = [a.lostAt, d.lostAt].compactMap { $0 }.min()
        // Время восстановления из журнала надёжнее вычисленного; из двух журнальных берётся более позднее.
        if a.restoredAt == nil { m.restoredAt = d.restoredAt; m.restoredInferred = d.restoredInferred }
        else if d.restoredAt == nil { m.restoredAt = a.restoredAt; m.restoredInferred = a.restoredInferred }
        else if a.restoredInferred != d.restoredInferred { m.restoredAt = a.restoredInferred ? d.restoredAt : a.restoredAt; m.restoredInferred = false }
        else if a.restoredInferred { m.restoredAt = a.restoredAt; m.restoredInferred = true }
        else { m.restoredAt = max(a.restoredAt!, d.restoredAt!); m.restoredInferred = false }
        m.kind = max(a.kind, d.kind)
        m.iface = a.iface.isEmpty ? d.iface : a.iface
        m.flaps = max(a.flaps, d.flaps)
        m.cause = a.cause ?? d.cause
        m.causeText = a.causeText.isEmpty ? d.causeText : a.causeText
        m.sessionMinutes = a.sessionMinutes ?? d.sessionMinutes
        m.lineHeld = a.lineHeld ?? d.lineHeld
        m.lines = Array((a.lines + d.lines.filter { !a.lines.contains($0) }).prefix(dropMaxLines))
        m.line = a.line ?? d.line
        m.crcDelta = a.crcDelta ?? d.crcDelta
        m.crcSince = a.crcSince ?? d.crcSince
        out[out.count - 1] = m
    }
    return out
}

/// Что роутер сообщает о соединении прямо сейчас.
public struct LinkStatus: Sendable {
    public var routerUptime: Int
    public var wanUp: Bool
    /// Сколько секунд держится текущее WAN-соединение (PPP-сессия или аренда IPoE).
    public var wanUptime: Int
    public var pppoe: Bool
    public var line: LineStats?
    public init(routerUptime: Int, wanUp: Bool, wanUptime: Int, pppoe: Bool, line: LineStats?) {
        self.routerUptime = routerUptime; self.wanUp = wanUp; self.wanUptime = wanUptime; self.pppoe = pppoe; self.line = line
    }
}

/// Дополняет инциденты тем, чего нет в журнале:
///  - линия DSL пересинхронизировалась или держалась (по времени её работы) — отсюда вид обрыва;
///  - время восстановления незакрытого инцидента — из времени работы WAN-соединения;
///  - переподключение, которого в журнале уже нет (буфер ушёл), — отдельным инцидентом без времени начала;
///  - состояние линии при первом чтении после обрыва и прирост ошибок CRC с прошлого замера `previousLine`.
public func withLinkStatus(_ drops: [ConnectionDrop], _ link: LinkStatus, now: Int, previousLine: LineStats? = nil) -> [ConnectionDrop] {
    var out = drops.sorted { $0.at < $1.at }
    /// Инцидент, к которому относится переподключение в момент `t`, или nil.
    func covering(_ t: Int) -> Int? {
        guard let i = out.lastIndex(where: { $0.at <= t + dropClockTolerance }), t <= (out[i].restoredAt ?? now) + dropGroupGap else { return nil }
        return i
    }

    let lineUptime = link.line.flatMap { $0.up ? $0.uptimeSec : nil }
    if let lineUptime {
        let lineStart = now - lineUptime
        let resynced = link.routerUptime - lineUptime > dropBootGrace
        let hit = resynced ? covering(lineStart) : nil
        for i in out.indices {
            if i == hit { out[i].kind = .dslLine; out[i].lineHeld = false }
            else if out[i].lineHeld == nil, out[i].at > lineStart + dropClockTolerance { out[i].lineHeld = true }
        }
        if resynced, hit == nil {
            out.append(ConnectionDrop(lostAt: nil, restoredAt: lineStart, restoredInferred: true, kind: .dslLine, lineHeld: false))
            out.sort { $0.at < $1.at }
        }
    }

    if link.wanUp, link.wanUptime > 0 {
        let wanStart = now - link.wanUptime
        if let hit = covering(wanStart) {
            if out[hit].restoredAt == nil, hit == out.count - 1 { out[hit].restoredAt = max(wanStart, out[hit].at); out[hit].restoredInferred = true }
        } else if link.routerUptime - link.wanUptime > dropBootGrace {
            let held = lineUptime.map { wanStart > now - $0 + dropClockTolerance }
            out.append(ConnectionDrop(lostAt: nil, restoredAt: wanStart, restoredInferred: true, kind: link.pppoe ? .pppSession : .wan, lineHeld: held))
            out.sort { $0.at < $1.at }
        }
    }

    if let last = out.last, let line = link.line, last.line == nil, let restored = last.restoredAt, now - restored <= dropLineStatsMaxAge {
        // Счётчики CRC сбрасываются при пересинхронизации, поэтому прирост считается только если линия держалась.
        let before = previousLine.flatMap { $0.capturedAt < last.at && last.lineHeld == true && line.crcDown >= $0.crcDown ? $0 : nil }
        out[out.count - 1].line = line
        out[out.count - 1].crcDelta = before.map { line.crcDown - $0.crcDown }
        out[out.count - 1].crcSince = before?.capturedAt
    }
    return out
}

/// Локальная история обрывов одного роутера и последний замер линии (для прироста ошибок к следующему обрыву).
public struct DropLog: Codable, Equatable, Sendable {
    public var drops: [ConnectionDrop] = []
    public var lastLine: LineStats?
    public init(drops: [ConnectionDrop] = [], lastLine: LineStats? = nil) { self.drops = drops; self.lastLine = lastLine }

    /// Учитывает новое чтение журнала: слияние без дублей, дополнение по состоянию связи, срок хранения.
    public func record(_ entries: [LogEntry], link: LinkStatus?, now: Int, calendar: Calendar = .current) -> DropLog {
        let merged = mergeDrops(drops, dropsFromLog(entries, now: now, calendar: calendar))
        let completed = link.map { withLinkStatus(merged, $0, now: now, previousLine: lastLine) } ?? merged
        return DropLog(drops: Array(completed.filter { $0.at >= now - dropRetentionDays * 86400 }.suffix(dropMaxStored)), lastLine: link?.line ?? lastLine)
    }

    /// Инциденты из `self`, которых не было в `before` (ни один прежний не пересекается с ними по времени).
    public func added(since before: DropLog) -> [ConnectionDrop] {
        drops.filter { d in !before.drops.contains { d.at <= $0.end + dropGroupGap && $0.at <= d.end + dropGroupGap } }
    }
}

/// Сколько обрывов началось (или обнаружено) за последние `seconds`.
public func dropsWithin(_ drops: [ConnectionDrop], now: Int, seconds: Int) -> Int { drops.filter { $0.at >= now - seconds }.count }

// MARK: - Текст для отчётов и интерфейса macOS

/// «38 с», «2 мин 0 с», дальше — как время работы.
public func formatShortDuration(_ seconds: Int) -> String {
    if seconds < 60 { return "\(seconds) с" }
    if seconds < 3600 { return "\(seconds / 60) мин \(seconds % 60) с" }
    return formatDuration(seconds)
}

public func formatDropTime(_ epoch: Int, calendar: Calendar = .current) -> String {
    let f = DateFormatter(); f.locale = Locale(identifier: "ru_RU"); f.calendar = calendar; f.timeZone = calendar.timeZone; f.dateFormat = "d MMM HH:mm:ss"
    return f.string(from: Date(timeIntervalSince1970: TimeInterval(epoch)))
}

extension ConnectionDrop {
    /// Когда случился обрыв; если начало в журнал не попало — примерное время восстановления.
    public func whenText(calendar: Calendar = .current) -> String {
        lostAt.map { formatDropTime($0, calendar: calendar) } ?? "≈ \(formatDropTime(at, calendar: calendar))"
    }

    /// «2 мин 0 с», «≈ 1 мин 0 с», «не восстановлена», «— (начала нет в журнале)».
    public var durationText: String {
        if let durationSec { return (restoredInferred ? "≈ " : "") + formatShortDuration(durationSec) }
        return restoredAt == nil ? "не восстановлена" : "—"
    }

    /// Время и длительность одной фразой: «22 нояб. 17:14:58, 2 мин 0 с»; неизвестная длительность опускается.
    public func whenWithDurationText(calendar: Calendar = .current) -> String {
        whenText(calendar: calendar) + (durationText == "—" ? "" : ", \(durationText)")
    }

    public var kindText: String {
        var parts = [kind.title]
        if !iface.isEmpty { parts.append(iface) }
        if flaps > 1 { parts.append("потерь подряд: \(flaps)") }
        if kind != .dslLine, lineHeld == true { parts.append("линия DSL синхронизацию держала") }
        return parts.joined(separator: ", ")
    }

    public var causeLabel: String { cause?.title ?? "роутер не записал" }

    /// Линия при первом чтении после обрыва: «↓ 184,4 / ↑ 55,6 Мбит/с, запас 6,3 / 7,5 дБ, CRC +5».
    public var lineText: String {
        guard let line else { return "—" }
        func n(_ v: Double?) -> String { v.map { String(format: "%.1f", $0).replacingOccurrences(of: ".", with: ",") } ?? "—" }
        var text = "↓ \(n(Double(line.downKbps) / 1000)) / ↑ \(n(Double(line.upKbps) / 1000)) Мбит/с, запас \(n(line.snrDown)) / \(n(line.snrUp)) дБ"
        if let crcDelta { text += ", CRC +\(crcDelta)" }
        return text
    }

    /// Все сведения об одном обрыве, по строке на показатель, — для журнала обрывов и окна подробностей.
    public func details(calendar: Calendar = .current) -> [(String, String)] {
        var rows: [(String, String)] = [
            ("Пропала", lostAt.map { formatDropTime($0, calendar: calendar) } ?? "нет в журнале"),
            ("Вернулась", restoredAt.map { (restoredInferred ? "≈ " : "") + formatDropTime($0, calendar: calendar) + (restoredInferred ? " (по времени работы соединения)" : "") } ?? "не восстановлена или нет в журнале"),
        ]
        if durationSec != nil { rows.append(("Длительность", durationText)) }
        rows.append(("Что случилось", kind.title))
        if !iface.isEmpty { rows.append(("Интерфейс", iface)) }
        if flaps > 1 { rows.append(("Потерь подряд", "\(flaps)")) }
        rows.append(("Причина", causeLabel))
        if let sessionMinutes { rows.append(("Сессия до обрыва", formatDuration(sessionMinutes * 60))) }
        if let lineHeld { rows.append(("Линия DSL", lineHeld ? "синхронизацию держала" : "синхронизировалась заново")) }
        if let line {
            func n(_ v: Double?) -> String { v.map { String(format: "%.1f", $0).replacingOccurrences(of: ".", with: ",") } ?? "—" }
            rows.append(("Замер линии", formatDropTime(line.capturedAt, calendar: calendar)))
            rows.append(("Синхронизация ↓ / ↑", "\(n(Double(line.downKbps) / 1000)) / \(n(Double(line.upKbps) / 1000)) Мбит/с"))
            rows.append(("Запас по шуму ↓ / ↑", "\(n(line.snrDown)) / \(n(line.snrUp)) дБ"))
            rows.append(("Затухание ↓ / ↑", "\(n(line.attnDown)) / \(n(line.attnUp)) дБ"))
            rows.append(("Мощность ↓ / ↑", "\(n(line.powerDown)) / \(n(line.powerUp)) дБм"))
            rows.append(("Ошибки CRC ↓ / ↑", "\(line.crcDown) / \(line.crcUp)"))
            rows.append(("Исправлено FEC ↓ / ↑", "\(line.fecDown) / \(line.fecUp)"))
            if let crcDelta, let crcSince { rows.append(("Новых ошибок CRC с \(formatDropTime(crcSince, calendar: calendar))", "\(crcDelta)")) }
            if let up = line.uptimeSec { rows.append(("Линия держит синхронизацию", formatDuration(up))) }
        }
        return rows
    }
}
