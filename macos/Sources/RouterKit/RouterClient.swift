import CommonCrypto
import Foundation
import Security

public enum RouterError: LocalizedError, Equatable {
    case badAddress
    case invalidCredentials
    case locked(Int)
    case sessionExpired
    case failed(String)

    public var errorDescription: String? {
        switch self {
        case .badAddress: return "Укажите локальный IP-адрес роутера, например 192.168.1.1."
        case .invalidCredentials: return "Неверное имя пользователя или пароль."
        case .locked(let seconds): return "Роутер временно заблокировал вход. Подождите \(seconds) с."
        case .sessionExpired: return "Сессия истекла."
        case .failed(let message): return message
        }
    }
}

/// The router uses a self-signed certificate, so it is accepted only for the private address the user entered.
private final class TrustDelegate: NSObject, URLSessionDelegate {
    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge) async -> (URLSession.AuthChallengeDisposition, URLCredential?) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              RouterClient.isPrivateIPv4(challenge.protectionSpace.host), let trust = challenge.protectionSpace.serverTrust
        else { return (.performDefaultHandling, nil) }
        return (.useCredential, URLCredential(trust: trust))
    }
}

public actor RouterClient {
    private let base: String
    private let session: URLSession
    private var cookies: [String: String] = [:]
    private var aesKey: String?
    private var csrf: String?

    /// Самотест крипто для юнит-тестов: AES-256-CBC с 32-байтным IV (используются первые 16) и отказ pkcs1 на мусоре.
    public static func selfTestCrypto() -> Bool {
        let key = Data((0..<32).map { UInt8($0) }).base64EncodedString()
        let iv = Data((0..<32).map { UInt8(255 - $0) }).base64EncodedString()
        let plain = Data("{\"p\":\"секрет\"}".utf8)
        guard let enc = aes(CCOperation(kCCEncrypt), input: plain, key: key, iv: iv),
              let dec = aes(CCOperation(kCCDecrypt), input: enc, key: key, iv: iv),
              dec == plain, pkcs1(fromSPKI: [0x00, 0x01, 0x02]) == nil,
              // SPKI с пустым BIT STRING (30 04 30 00 03 00) не должен ронять разбор.
              pkcs1(fromSPKI: [0x30, 0x04, 0x30, 0x00, 0x03, 0x00]) == nil else { return false }
        return true
    }

    public static func isPrivateIPv4(_ host: String) -> Bool {
        let parts = host.split(separator: ".").compactMap { Int($0) }
        guard parts.count == 4, parts.allSatisfy({ (0...255).contains($0) }) else { return false }
        return parts[0] == 10 || parts[0] == 127 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && (16...31).contains(parts[1]))
    }

    /// `address` is an IPv4 address with an optional port.
    public init(address: String) throws {
        let trimmed = address.trimmingCharacters(in: .whitespaces)
        guard RouterClient.isPrivateIPv4(trimmed.split(separator: ":").first.map(String.init) ?? "") else { throw RouterError.badAddress }
        base = "https://" + trimmed
        let config = URLSessionConfiguration.ephemeral
        config.httpShouldSetCookies = false
        config.httpCookieAcceptPolicy = .never
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        session = URLSession(configuration: config, delegate: TrustDelegate(), delegateQueue: nil)
    }

    private func send(_ method: String, _ path: String, body: Data? = nil, timeout: TimeInterval = 30) async throws -> (Int, Any?) {
        guard let url = URL(string: base + path) else { throw RouterError.badAddress }
        var request = URLRequest(url: url, timeoutInterval: timeout)
        request.httpMethod = method
        request.httpBody = body
        request.setValue("Thu, 01 Jun 1970 00:00:00 GMT", forHTTPHeaderField: "If-Modified-Since")
        if body != nil { request.setValue("application/x-www-form-urlencoded; charset=UTF-8", forHTTPHeaderField: "Content-Type") }
        if !cookies.isEmpty { request.setValue(cookies.map { "\($0.key)=\($0.value)" }.joined(separator: "; "), forHTTPHeaderField: "Cookie") }
        if method != "GET", path.contains("cgi-bin"), let csrf { request.setValue(csrf, forHTTPHeaderField: "CSRFToken") }

        let data: Data, response: URLResponse
        do { (data, response) = try await session.data(for: request) } catch {
            throw RouterError.failed("Роутер недоступен: \(error.localizedDescription)")
        }
        guard let http = response as? HTTPURLResponse else { throw RouterError.failed("Некорректный ответ роутера.") }
        let headers = http.allHeaderFields.reduce(into: [String: String]()) { $0["\($1.key)"] = "\($1.value)" }
        for cookie in HTTPCookie.cookies(withResponseHeaderFields: headers, for: url) { cookies[cookie.name] = cookie.value }

        var json = try? JSONSerialization.jsonObject(with: data)
        if let d = json as? [String: Any], let content = d["content"] as? String, let iv = d["iv"] as? String, let aesKey,
           path == "/UserLogin" || path.contains("cgi-bin") {
            guard let plain = Self.aes(CCOperation(kCCDecrypt), input: Data(base64Encoded: content), key: aesKey, iv: iv) else {
                throw RouterError.failed("Не удалось расшифровать ответ роутера.")
            }
            json = try? JSONSerialization.jsonObject(with: plain)
        }
        // Роутер выдаёт новый CSRF-токен после каждой записи; без этого следующая запись вернёт 403.
        let newKey = Field.str(Field.dict(json)["sessionkey"])
        if !newKey.isEmpty { csrf = newKey }
        return (http.statusCode, json)
    }

    public func basicInfo() async throws -> RouterBasic {
        let (_, json) = try await send("GET", "/getBasicInformation", timeout: 8)
        let d = Field.dict(json)
        return RouterBasic(model: Field.str(d["ModelName"]), firmware: Field.str(d["SoftwareVersion"]), language: Field.str(d["CurrentLanguage"]))
    }

    public func publicKeyLooksValid() async throws -> Bool {
        let (_, json) = try await send("GET", "/getRSAPublickKey", timeout: 8)
        let pem = Field.str(Field.dict(json)["RSAPublicKey"])
        return (try? Self.rsaEncrypt(Data("test".utf8), pem: pem)) != nil
    }

    public func login(username: String, password: String) async throws {
        let basic = try await basicInfo()
        let (_, keyJSON) = try await send("GET", "/getRSAPublickKey", timeout: 15)
        let pem = Field.str(Field.dict(keyJSON)["RSAPublicKey"])
        let payload: [String: Any] = [
            "Input_Account": username, "Input_Passwd": Data(password.utf8).base64EncodedString(),
            "currLang": basic.language.isEmpty ? "en" : basic.language, "RememberPassword": 0, "SHA512_password": false,
        ]
        let plain = try JSONSerialization.data(withJSONObject: payload)
        cookies = [:]
        csrf = nil
        aesKey = nil   // не шифровать устаревшим ключом, если сессия вдруг окажется без шифрования
        var body = plain
        if !pem.isEmpty, pem != "None" {
            let key = Self.randomBase64()
            let iv = Self.randomBase64()
            guard let encrypted = Self.aes(CCOperation(kCCEncrypt), input: plain, key: key, iv: iv) else { throw RouterError.failed("Ошибка шифрования.") }
            let wrapped = try Self.rsaEncrypt(Data(key.utf8), pem: pem)
            aesKey = key
            body = try JSONSerialization.data(withJSONObject: [
                "content": encrypted.base64EncodedString(), "key": wrapped.base64EncodedString(), "iv": iv,
            ])
        }
        let (status, json) = try await send("POST", "/UserLogin", body: body)
        let d = Field.dict(json)
        guard (200..<300).contains(status) else {
            let result = Field.str(d["result"])
            switch result {
            case "Invalid Username or Password", "HTTP permission denied": throw RouterError.invalidCredentials
            case "Locked User":
                throw RouterError.locked(Int(Field.str(d["replyMsg"]).split(separator: "=").last ?? "") ?? 0)
            case "Duplicated login", "A user exist": throw RouterError.failed("На роутере уже открыта другая сессия.")
            case "Maxium number of login account has reached": throw RouterError.failed("На роутере открыто максимальное число сессий.")
            default: throw RouterError.failed("Вход не выполнен: \(result.isEmpty ? "HTTP \(status)" : result)")
            }
        }
        csrf = Field.str(d["sessionkey"])
    }

    private func dal(_ oid: String) async throws -> [String: Any] {
        let (status, json) = try await send("GET", "/cgi-bin/DAL?oid=\(oid)")
        if [401, 403, 504].contains(status) { throw RouterError.sessionExpired }
        let d = Field.dict(json)
        guard status == 200, Field.str(d["result"]) == "ZCFG_SUCCESS", let first = Field.list(d["Object"]).first else {
            throw RouterError.failed("Роутер не вернул данные \(oid) (HTTP \(status)).")
        }
        return first
    }

    public func snapshot() async throws -> RouterSnapshot {
        let status = try await dal("cardpage_status")
        let hosts = try await dal("lanhosts")
        return parseSnapshot(status: status, hosts: hosts)
    }

    /// Возвращает массив Object для oid, или бросает при ошибке/истёкшей сессии.
    private func dalObjects(_ oid: String) async throws -> [[String: Any]] {
        let (status, json) = try await send("GET", "/cgi-bin/DAL?oid=\(oid)")
        if [401, 403, 504].contains(status) { throw RouterError.sessionExpired }
        let d = Field.dict(json)
        guard status == 200, Field.str(d["result"]) == "ZCFG_SUCCESS" else {
            throw RouterError.failed("Роутер не вернул данные \(oid) (HTTP \(status)).")
        }
        return Field.list(d["Object"])
    }

    /// Запись в DAL: тело шифруется тем же ключом AES, что и при входе, с пустым полем key — как это делает веб-интерфейс.
    @discardableResult
    private func dalWrite(_ method: String, oid: String, obj: Any) async throws -> [String: Any] {
        let payload = try JSONSerialization.data(withJSONObject: obj)
        var body = payload
        if let aesKey {
            let iv = Self.randomBase64()
            guard let enc = Self.aes(CCOperation(kCCEncrypt), input: payload, key: aesKey, iv: iv) else { throw RouterError.failed("Ошибка шифрования.") }
            body = try JSONSerialization.data(withJSONObject: ["content": enc.base64EncodedString(), "key": "", "iv": iv])
        }
        let (status, json) = try await send(method, "/cgi-bin/DAL?oid=\(oid)", body: body)
        if [401, 403, 504].contains(status) { throw RouterError.sessionExpired }
        let d = Field.dict(json)
        guard (200..<300).contains(status), Field.str(d["result"]) == "ZCFG_SUCCESS" else {
            throw RouterError.failed("Роутер отклонил изменение \(oid) (HTTP \(status)).")
        }
        return d
    }

    public func parentalControl() async throws -> ParentalControl {
        parseParental(try await dalObjects("paren_ctl"))
    }

    public func setParentalEnabled(_ on: Bool) async throws {
        try await dalWrite("PUT", oid: "paren_ctl", obj: ["PrentalCtlEnable": on ? "1" : "0"])
    }

    /// Включает или выключает профиль: читает его текущий объект и возвращает целиком с изменённым флагом.
    public func setProfileEnabled(index: Int, on: Bool) async throws {
        let objects = try await dalObjects("paren_ctl")
        guard var obj = objects.first(where: { Field.int($0["Index"]) == index }) else {
            throw RouterError.failed("Профиль не найден; обновите список.")
        }
        obj["pcpEnable"] = on ? 1 : 0
        try await dalWrite("PUT", oid: "paren_ctl", obj: obj)
    }

    /// Создаёт (index == nil) или изменяет профиль. MAC-адреса с двоеточиями, дни кодами Mon..Sun,
    /// timeRangeList — готовая строка интервалов "ЧЧ:ММ-ЧЧ:ММ[,...]".
    /// При изменении читается текущий объект и правятся только эти поля, чтобы не стереть фильтры
    /// сайтов и сервисов, заданные в веб-интерфейсе.
    public func saveProfile(index: Int?, name: String, macs: [String], days: [String], timeRangeList: String, enabled: Bool) async throws {
        if let index {
            let objects = try await dalObjects("paren_ctl")
            guard var obj = objects.first(where: { Field.int($0["Index"]) == index }) else {
                throw RouterError.failed("Профиль не найден; обновите список.")
            }
            obj["pcpName"] = name
            obj["pcpEnable"] = enabled ? 1 : 0
            obj["MACAddressList"] = macs.joined(separator: ",")
            obj["Days"] = days
            obj["TimeRangeList"] = timeRangeList
            try await dalWrite("PUT", oid: "paren_ctl", obj: obj)
        } else {
            let obj: [String: Any] = [
                "pcpName": name, "pcpEnable": enabled ? 1 : 0,
                "MACAddressList": macs.joined(separator: ","), "Days": days, "TimeRangeList": timeRangeList,
                "ServicePolicy": "Block", "UrlPolicy": "Block", "WebRedirect": 0, "URLFilter": "", "NetworkServiceList": "",
            ]
            try await dalWrite("POST", oid: "paren_ctl", obj: obj)
        }
    }

    public func deleteProfile(index: Int) async throws {
        let (status, json) = try await send("DELETE", "/cgi-bin/DAL?oid=paren_ctl&Index=\(index)")
        if [401, 403, 504].contains(status) { throw RouterError.sessionExpired }
        guard (200..<300).contains(status), Field.str(Field.dict(json)["result"]) == "ZCFG_SUCCESS" else {
            throw RouterError.failed("Роутер отклонил удаление профиля (HTTP \(status)).")
        }
    }

    /// Произвольный GET к /cgi-bin (ответ расшифровывается как обычно) — для снятия формата журнала и т.п.
    public func rawGet(_ path: String) async throws -> Any? {
        let (status, json) = try await send("GET", path)
        if [401, 403, 504].contains(status) { throw RouterError.sessionExpired }
        guard status == 200 else { throw RouterError.failed("HTTP \(status) для \(path).") }
        return json
    }

    /// Сырой ответ DAL целиком (result + Object) — для снятия реального формата в тестовые фикстуры.
    public func rawDAL(_ oid: String) async throws -> [String: Any] {
        let (status, json) = try await send("GET", "/cgi-bin/DAL?oid=\(oid)")
        if [401, 403, 504].contains(status) { throw RouterError.sessionExpired }
        guard status == 200, let d = json as? [String: Any] else {
            throw RouterError.failed("Нет данных \(oid) (HTTP \(status)).")
        }
        return d
    }

    /// Читает системный журнал роутера (iid [1] — системный, [2] — безопасность).
    public func systemLog(security: Bool = false) async throws -> [LogEntry] {
        let iid = security ? "2" : "1"
        let raw = try await rawGet("/cgi-bin/LogResult?action=GET_LOG&oid=RDM_OID_LOG_CLASSIFY&iid=[\(iid),0,0,0,0,0]")
        return parseLog(raw)
    }

    public func routing() async throws -> Routing {
        parseRouting(
            static: try await dalObjects("static_route"), dns: try await dalObjects("dns_route"),
            policy: try await dalObjects("policy_route"), rip: try await dalObjects("rip"))
    }

    public func reboot() async throws {
        let (status, _) = try await send("POST", "/cgi-bin/Reboot")
        if [401, 403, 504].contains(status) { throw RouterError.sessionExpired }
        guard (200..<300).contains(status) else { throw RouterError.failed("Роутер отклонил перезагрузку (HTTP \(status)).") }
    }

    public func logout() async {
        _ = try? await send("POST", "/cgi-bin/UserLogout", timeout: 3)
        cookies = [:]
        csrf = nil
        aesKey = nil
    }

    private static func randomBase64() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes).base64EncodedString()
    }

    /// AES-256-CBC. The router sends 32-byte IVs and, like CryptoJS, only the first block is used.
    private static func aes(_ op: CCOperation, input: Data?, key: String, iv: String) -> Data? {
        guard let input, let keyData = Data(base64Encoded: key), let ivData = Data(base64Encoded: iv), ivData.count >= 16 else { return nil }
        var output = Data(count: input.count + kCCBlockSizeAES128)
        var written = 0
        let capacity = output.count
        let status = output.withUnsafeMutableBytes { out in
            input.withUnsafeBytes { inp in
                keyData.withUnsafeBytes { k in
                    ivData.withUnsafeBytes { i in
                        CCCrypt(op, CCAlgorithm(kCCAlgorithmAES), CCOptions(kCCOptionPKCS7Padding), k.baseAddress, keyData.count, i.baseAddress,
                                inp.baseAddress, input.count, out.baseAddress, capacity, &written)
                    }
                }
            }
        }
        return status == CCCryptorStatus(kCCSuccess) ? output.prefix(written) : nil
    }

    /// Finds the PKCS#1 RSAPublicKey inside a SubjectPublicKeyInfo DER blob.
    private static func pkcs1(fromSPKI der: [UInt8]) -> Data? {
        func header(_ at: Int) -> (tag: UInt8, body: Int, length: Int)? {
            guard at + 1 < der.count else { return nil }
            let first = Int(der[at + 1])
            if first < 0x80 { return (der[at], at + 2, first) }
            let count = first & 0x7f
            guard at + 2 + count <= der.count else { return nil }
            return (der[at], at + 2 + count, der[(at + 2)..<(at + 2 + count)].reduce(0) { $0 << 8 | Int($1) })
        }
        guard let outer = header(0), outer.tag == 0x30, let algorithm = header(outer.body), algorithm.tag == 0x30,
              let bits = header(algorithm.body + algorithm.length), bits.tag == 0x03,
              // BIT STRING: минимум байт «неиспользуемых бит»; пустой/короткий ключ не должен ронять приложение.
              bits.length >= 1, bits.body + bits.length <= der.count
        else { return nil }
        return Data(der[(bits.body + 1)..<(bits.body + bits.length)])
    }

    private static func rsaEncrypt(_ data: Data, pem: String) throws -> Data {
        let base64 = pem.split(whereSeparator: \.isNewline).filter { !$0.hasPrefix("-----") }.joined()
        guard let der = Data(base64Encoded: base64), let rsa = pkcs1(fromSPKI: [UInt8](der)) else { throw RouterError.failed("Некорректный ключ роутера.") }
        let attributes: [CFString: Any] = [kSecAttrKeyType: kSecAttrKeyTypeRSA, kSecAttrKeyClass: kSecAttrKeyClassPublic]
        guard let key = SecKeyCreateWithData(rsa as CFData, attributes as CFDictionary, nil),
              let out = SecKeyCreateEncryptedData(key, .rsaEncryptionPKCS1, data as CFData, nil)
        else { throw RouterError.failed("Ошибка шифрования RSA.") }
        return out as Data
    }
}
