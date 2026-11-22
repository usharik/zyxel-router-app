// Test double of the Zyxel VMG8623-T50B web API. Follows the protocol read from the router's own web UI.
// Usage: node mock/mock_router.js [port]. Login: admin / testpass.
const https = require('https'), crypto = require('crypto'), fs = require('fs'), path = require('path'), cp = require('child_process');
const port = Number(process.argv[2] || 8443);
const certFile = path.join(__dirname, 'cert.pem'), keyFile = path.join(__dirname, 'key.pem');
if (!fs.existsSync(certFile)) cp.execSync(`openssl req -x509 -newkey rsa:2048 -nodes -keyout "${keyFile}" -out "${certFile}" -days 30 -subj /CN=mock-router`, { stdio: 'ignore' });
const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
const pem = publicKey.export({ type: 'spki', format: 'pem' });
const aesEnc = (text, keyB64) => {
  if (!keyB64) return JSON.parse(text);   // вход без RSA: роутер отвечает открытым JSON
  const ivFull = crypto.randomBytes(32), c = crypto.createCipheriv('aes-256-cbc', Buffer.from(keyB64, 'base64'), ivFull.subarray(0, 16));
  return { content: Buffer.concat([c.update(text), c.final()]).toString('base64'), iv: ivFull.toString('base64') };
};
const aesDec = (content, keyB64, ivB64) => {
  const d = crypto.createDecipheriv('aes-256-cbc', Buffer.from(keyB64, 'base64'), Buffer.from(ivB64, 'base64').subarray(0, 16));
  return Buffer.concat([d.update(Buffer.from(content, 'base64')), d.final()]).toString();
};
let session = null, fails = 0;
// TLS-соединения, по которым шли запросы приложения (не /__test): тест проверяет, что клиент их переиспользует.
let appSockets = new Set();
// Переключатели для автотестов клиента (POST /__test/...): ошибки разделов, испорченный вход, вход без RSA.
let test = { status: {}, garbageLogin: false, noRsa: false };
const status = { result: 'ZCFG_SUCCESS', Object: [{
  DeviceInfo: { ModelName: 'VMG8623-T50B', SoftwareVersion: 'V5.50(ABPM.7.3)V1', UpTime: 200000, SerialNumber: 'S000' },
  WanType: 'VDSL', WanRate_RX: 0, WanRate_TX: 0,
  DslChannelInfo: [{ Status: 'Up', DownstreamCurrRate: 98304, UpstreamCurrRate: 20480, LinkEncapsulationUsed: 'G.993.2_Annex_K_PTM' }, { Status: 'Down', DownstreamCurrRate: 0, UpstreamCurrRate: 0 }],
  WanLanInfo: [
    { Status: 'Up', LinkType: 'None', X_ZYXEL_SrvName: 'Default', IPv4Address: [{ IPAddress: '192.168.1.1' }], dnsv4Server: [] },
    { Status: 'Up', LinkType: '', X_ZYXEL_SrvName: 'LAN', Encapsulation: '', DefaultGateway: false, IPv4Address: [{ IPAddress: '192.168.1.1' }], dnsv4Server: [] },
    { Status: 'Up', LinkType: 'PTM', X_ZYXEL_SrvName: 'Internet', Encapsulation: 'PPPoE', DefaultGateway: true, pppoeConnectionUpTime: 3700, IPv4Address: [{ IPAddress: '203.0.113.7' }], dnsv4Server: [{ DNSServer: '1.1.1.1' }] }],
  WiFiInfo: [
    { SSID: 'Home', Enable: 1, Channel: 6, OperatingStandards: 'b,g,n', ModeEnabled: 'WPA2-Personal', wifiPassword: 'mock-home-24' },
    { SSID: 'Guest', Enable: 0, Channel: 6, OperatingStandards: 'b,g,n', ModeEnabled: 'WPA2-Personal', wifiPassword: 'mock-guest' },
    { SSID: 'Home-5G', Enable: true, Channel: 44, OperatingStandards: 'a,n,ac', ModeEnabled: 'WPA2-Personal', wifiPassword: 'mock;home:5' },
    { SSID: 'Guest-5G', Enable: false, Channel: 44, OperatingStandards: 'a,n,ac', ModeEnabled: 'None', wifiPassword: '' }],
}] };
const hosts = { result: 'ZCFG_SUCCESS', Object: [{ wanInfo: { wanStatus: 1 }, lanhosts: [
  { Active: true, curHostName: 'MacBook &amp; Co', HostName: 'mac', IPAddress: '192.168.1.52', IPAddress6: 'fe80::1', PhysAddress: '14:33:75:00:00:01', X_ZYXEL_ConnectionType: 'Wi-Fi 5GHz', Layer1Interface: 'Device.WiFi.SSID.5', VendorClassID: '*', X_ZYXEL_SignalStrength: 4, X_ZYXEL_SNR: 34, X_ZYXEL_PhyRate: 585, X_ZYXEL_OperatingStandard: 'ac', X_ZYXEL_BytesReceived: 106174729, X_ZYXEL_BytesSent: 1788431209, LeaseTimeRemaining: 64036, Internet_Blocking_Enable: false },
  { Active: 1, curHostName: '', HostName: 'tv-box', IPAddress: '192.168.1.60', PhysAddress: '14:33:75:00:00:02', X_ZYXEL_ConnectionType: 'Ethernet', Layer1Interface: 'Device.Ethernet.Interface.1', VendorClassID: 'atvbox-4403-czsk', X_ZYXEL_SignalStrength: 0, X_ZYXEL_BytesReceived: 16061331, X_ZYXEL_BytesSent: 278650487 },
  { Active: 1, curHostName: '', HostName: '', DeviceName: 'Phone guest', IPAddress: '192.168.2.33', PhysAddress: '14:33:75:00:00:03', X_ZYXEL_ConnectionType: 'Wi-Fi 2.4GHz', Layer1Interface: 'WiFi.SSID.2', VendorClassID: 'android-dhcp-14', X_ZYXEL_SignalStrength: 5, X_ZYXEL_SNR: 29, X_ZYXEL_PhyRate: 144, X_ZYXEL_OperatingStandard: 'n', X_ZYXEL_BytesReceived: 1182148, X_ZYXEL_BytesSent: 24398, Internet_Blocking_Enable: true },
  { Active: 1, curHostName: 'XRX9C-print', HostName: '', IPAddress: '192.168.1.70', PhysAddress: '14:33:75:00:00:05', X_ZYXEL_ConnectionType: 'Wi-Fi 2.4GHz', Layer1Interface: 'Device.WiFi.SSID.1', VendorClassID: 'XEROX Network Printer', X_ZYXEL_SignalStrength: 3, X_ZYXEL_SNR: 21, X_ZYXEL_PhyRate: 54, X_ZYXEL_OperatingStandard: 'g', X_ZYXEL_BytesReceived: 271612, X_ZYXEL_BytesSent: 140293 },
  { Active: false, curHostName: '', HostName: '', IPAddress: '', PhysAddress: '14:33:75:00:00:04', X_ZYXEL_ConnectionType: 'Wi-Fi 2.4GHz', Layer1Interface: 'Device.WiFi.SSID.1', VendorClassID: '*' }] }] };
// Родительский контроль: Object[0] — общий статус и предел профилей, далее профили.
const parental = { result: 'ZCFG_SUCCESS', Object: [
  { PrentalCtlEnable: true, MaxLenPrentalCtlPrf: 12 },
  { Index: 1, pcpEnable: true, pcpName: 'Дети', MACAddressList: '14:33:75:00:00:03', Days: 'Mon,Tue,Wed,Thu,Fri', TimeRangeList: '21:00-23:59', NetworkServiceList: '', URLFilter: 'tiktok.com,youtube.com', ServicePolicy: 'Block', UrlPolicy: 'Block', WebRedirect: false, ScheduleRuleList: 'X_ZYXEL_Schedule.1' },
  { Index: 2, pcpEnable: false, pcpName: 'Гостевой ТВ', MACAddressList: '14:33:75:00:00:02', Days: 'Sat,Sun', TimeRangeList: '00:00-08:00', NetworkServiceList: '', URLFilter: '', ServicePolicy: 'Block', UrlPolicy: 'Block', WebRedirect: false, ScheduleRuleList: 'X_ZYXEL_Schedule.2' }] };
const staticRoute = { result: 'ZCFG_SUCCESS', Object: [
  { Index: 0, Enable: true, Name: 'office', ipver: 'IPv4', DestIPAddress: '10.20.0.0', DestSubnetMask: '255.255.255.0', GatewayIPAddress: '192.168.1.2', Interface: '', DestIPPrefix: '' }] };
const dnsRoute = { result: 'ZCFG_SUCCESS', Object: [
  { Index: 1, Enable: true, DomainName: 'work.example.com', Interface: 'Device.IP.Interface.1', SubnetMask: '255.255.255.255' }] };
const policyRoute = { result: 'ZCFG_SUCCESS', Object: [
  { Index: 0, Enable: true, RouteName: 'kids-vpn', SourceIpAddress: '192.168.1.60', SourceSubnetMask: '255.255.255.255', Protocol: 'ALL', SourcePort: 0, SourceMacAddress: '', SourceIfName: '', WANInterface: 'Device.IP.Interface.1' }] };
const rip = { result: 'ZCFG_SUCCESS', Object: [
  { Interface: 'ADSL', Version: 'RIPv2', RipOpMode: 'Active', Enable: false, DisableDefaultGw: false },
  { Interface: 'VDSL', Version: 'RIPv2', RipOpMode: 'Passive', Enable: false, DisableDefaultGw: false },
  { Interface: 'ETHWAN', Version: 'RIPv1', RipOpMode: 'Passive', Enable: false, DisableDefaultGw: false },
  { Interface: 'Default', Version: 'RIPv2', RipOpMode: 'Active', Enable: false, DisableDefaultGw: false },
  { Interface: 'USB', Version: 'RIPv2', RipOpMode: 'Passive', Enable: false, DisableDefaultGw: false }] };
const dalGet = { paren_ctl: parental, static_route: staticRoute, dns_route: dnsRoute, policy_route: policyRoute, rip };
const parentalInitial = JSON.stringify(parental.Object);
// Настоящий роутер возвращает Days строкой через запятую, даже если на запись пришёл массив.
const norm = (o) => (Array.isArray(o.Days) ? { ...o, Days: o.Days.join(',') } : o);
https.createServer({ key: fs.readFileSync(keyFile), cert: fs.readFileSync(certFile) }, (req, res) => {
  let body = ''; req.on('data', d => body += d); req.on('end', () => {
    const send = (code, obj, headers = {}) => { res.writeHead(code, { 'Content-Type': 'application/json', ...headers }); res.end(JSON.stringify(obj)); console.log(req.method, req.url, code); };
    const authed = session && (req.headers.cookie || '').includes(`Session=${session.id}`);
    const url = req.url.split('&')[0];
    if (url === '/__test/reset' && req.method === 'POST') {
      session = null; fails = 0; appSockets = new Set(); test = { status: {}, garbageLogin: false, noRsa: false };
      parental.Object.splice(0, parental.Object.length, ...JSON.parse(parentalInitial));
      return send(200, { result: 'ok' });
    }
    if (url === '/__test/config' && req.method === 'POST') { Object.assign(test, JSON.parse(body || '{}')); return send(200, { result: 'ok' }); }
    if (url === '/__test/expire' && req.method === 'POST') { session = null; return send(200, { result: 'ok' }); }
    if (url === '/__test/state') return send(200, { session: !!session, fails, csrf: session && session.csrf, profiles: parental.Object.slice(1), connections: appSockets.size });
    if (!url.startsWith('/__test/')) appSockets.add(req.socket);
    if (url === '/getBasicInformation') return send(200, { result: 'ZCFG_SUCCESS', ModelName: 'VMG8623-T50B', SoftwareVersion: 'V5.50(ABPM.7.3)V1', CurrentLanguage: 'cz' });
    if (url === '/getRSAPublickKey') return send(200, { RSAPublicKey: test.noRsa ? 'None' : pem, result: 'ZCFG_SUCCESS' });
    if (url === '/UserLogin' && req.method === 'POST') {
      let o, aes;
      if (test.garbageLogin) return send(200, { iv: crypto.randomBytes(32).toString('base64'), content: 'bm90IGVuY3J5cHRlZA==' });
      if (test.noRsa) {
        o = JSON.parse(body);
        if (o.Input_Account !== 'admin' || Buffer.from(o.Input_Passwd, 'base64').toString() !== 'testpass') { fails++; return send(401, { result: 'Invalid Username or Password' }); }
        session = { id: crypto.randomBytes(8).toString('hex'), csrf: crypto.randomBytes(8).toString('hex'), aes: null };
        return send(200, { result: 'ZCFG_SUCCESS', sessionkey: session.csrf }, { 'Set-Cookie': `Session=${session.id}; Path=/; Secure; HttpOnly` });
      }
      try {
        const e = JSON.parse(body);
        aes = crypto.privateDecrypt({ key: privateKey, padding: crypto.constants.RSA_PKCS1_PADDING }, Buffer.from(e.key, 'base64')).toString();
        if (Buffer.from(aes, 'base64').length !== 32) throw new Error('AES key is not 32 bytes');
        o = JSON.parse(aesDec(e.content, aes, e.iv));
      } catch (err) { console.log('BAD LOGIN ENVELOPE', err.message); return send(400, { result: 'bad envelope' }); }
      if (fails >= 3) return send(401, { result: 'Locked User', replyMsg: 'lockTime=60' });
      if (o.Input_Account !== 'admin' || Buffer.from(o.Input_Passwd, 'base64').toString() !== 'testpass' || o.SHA512_password !== false) { fails++; return send(401, { result: 'Invalid Username or Password' }); }
      fails = 0; session = { id: crypto.randomBytes(8).toString('hex'), csrf: crypto.randomBytes(8).toString('hex'), aes };
      return send(200, aesEnc(JSON.stringify({ result: 'ZCFG_SUCCESS', sessionkey: session.csrf, loginAccount: 'admin', loginLevel: 'high' }), aes), { 'Set-Cookie': `Session=${session.id}; Path=/; Secure; HttpOnly` });
    }
    if (url.startsWith('/cgi-bin/')) {
      if (!authed) return send(401, { result: 'Invalid Username or Password' });
      const testOid = (url.match(/\/cgi-bin\/DAL\?oid=([a-z_]+)/) || [])[1];
      if (req.method === 'GET' && testOid && test.status[testOid]) return send(test.status[testOid], { result: 'ZCFG_NO_SUCH_OBJECT' });
      // Журнал: ответ — зашифрованный JSON-массив строк (как настоящий GET_LOG).
      if (url.startsWith('/cgi-bin/LogResult')) {
        const lines = [
          '[dhcpd] Nov  8 16:54:14 daemon.info dnsmasq-dhcp: lease added 192.168.1.60',
          '[tr69] Nov  8 10:00:00 daemon.err cwmp: connection to ACS failed',
          '[dhcpc] Nov  7 09:00:00 daemon.debug udhcpc: sending renew',
          '[kernel] Nov  7 08:00:00 kern.warning kernel: wlan0 rate limited',
          '[firewall] Oct 31 23:00:00 daemon.notice firewall: dropped inbound',
          // Обрыв связи в формулировках настоящего VMG8623-T50B: сессия PPP упала, через 40 секунд связь вернулась.
          '[pppoe] Nov  9 03:10:00 daemon.info pppd: Connect time 1440.0 minutes.',
          '[pppoe] Nov  9 03:10:00 daemon.notice pppd: Json PPP {    "ip": "203.0.113.7",    "linkname": "pppd3",    "type": "ipv4",    "state": "down"  }',
          '[system] Nov  9 03:10:02 user.notice zcmdModuleCfg: System: Wan interface VDSL connection lost',
          '[system] Nov  9 03:10:40 user.notice zcmdModuleCfg: System: Wan interface VDSL connection ready',
        ];
        return send(200, aesEnc(JSON.stringify([{ Iid: [1, 0, 0, 0, 0, 0], Oid: 'RDM_OID_LOG_CLASSIFY', result: lines }]), session.aes));
      }
      // Статистика линии DSL: как у настоящего роутера, текст в поле result.
      if (url.startsWith('/cgi-bin/xDSLStatistics_handle')) {
        if (test.status.xdsl) return send(test.status.xdsl, { result: 'ZCFG_NO_SUCH_OBJECT' });
        const text = [
          '       Line State:      Up', '       Modulation:      ITU G.993.5(G.Vectoring),G.998.4(G.INP)',
          'near-end interleaved channel bit rate: 98304 kbps', 'near-end fast channel bit rate: 0 kbps',
          'far-end interleaved channel bit rate: 0 kbps', 'far-end fast channel bit rate: 20480 kbps',
          'near-end CRC error interleaved: 45', 'far-end FEC error fast: 3449', 'DSL uptime :14 days,  5:15, 21 secs',
          'noise margin downstream: 6.3 dB', 'attenuation downstream: 9.7 dB', 'noise margin upstream: 7.5 dB', '',
        ].join('\n');
        return send(200, aesEnc(JSON.stringify([{ result: text }]), session.aes));
      }
      if (req.url === '/cgi-bin/DAL?oid=cardpage_status') return send(200, aesEnc(JSON.stringify(status), session.aes));
      if (req.url === '/cgi-bin/DAL?oid=lanhosts') return send(200, aesEnc(JSON.stringify(hosts), session.aes));
      const oidGet = (url.match(/\/cgi-bin\/DAL\?oid=([a-z_]+)/) || [])[1];
      if (req.method === 'GET' && oidGet && dalGet[oidGet]) return send(200, aesEnc(JSON.stringify(dalGet[oidGet]), session.aes));
      // Записи требуют CSRF и вращают ключ сессии, как настоящий роутер.
      if ((req.method === 'PUT' || req.method === 'POST' || req.method === 'DELETE') && oidGet) {
        if (req.headers.csrftoken !== session.csrf) return send(403, { result: 'bad csrf' });
        // DELETE приходит как ?oid=...&Index=N без тела; остальное — зашифрованное тело.
        const qIndex = Number((req.url.match(/[?&]Index=(\d+)/) || [])[1]);
        const obj = body && session.aes && JSON.parse(body).content ? JSON.parse(aesDec(JSON.parse(body).content, session.aes, JSON.parse(body).iv)) : (body ? JSON.parse(body) : {});
        if (oidGet === 'paren_ctl') {
          if (req.method === 'PUT' && 'PrentalCtlEnable' in obj && !('pcpName' in obj)) parental.Object[0].PrentalCtlEnable = obj.PrentalCtlEnable === '1' || obj.PrentalCtlEnable === 1 || obj.PrentalCtlEnable === true;
          // Формат записи, подтверждённый на роутере, — массив; строку отвергаем, чтобы тесты ловили регрессию.
          else if (req.method === 'PUT' && 'Index' in obj && typeof obj.Days === 'string') return send(200, aesEnc(JSON.stringify({ result: 'ZCFG_INVALID_PARAM_VALUE' }), session.aes));
          else if (req.method === 'PUT' && 'Index' in obj) { const p = parental.Object.find(x => x.Index === obj.Index); if (p) Object.assign(p, norm(obj)); }
          else if (req.method === 'POST') { const idx = Math.max(0, ...parental.Object.slice(1).map(x => x.Index)) + 1; parental.Object.push({ ...norm(obj), Index: idx }); }
          else if (req.method === 'DELETE') { const i = parental.Object.findIndex(x => x.Index === qIndex); if (i > 0) parental.Object.splice(i, 1); }
        }
        session.csrf = crypto.randomBytes(8).toString('hex');
        return send(200, aesEnc(JSON.stringify({ result: 'ZCFG_SUCCESS', sessionkey: session.csrf }), session.aes));
      }
      if (req.method === 'POST' && (url === '/cgi-bin/Reboot' || url === '/cgi-bin/UserLogout')) {
        if (req.headers.csrftoken !== session.csrf) return send(403, { result: 'bad csrf' });
        if (url === '/cgi-bin/UserLogout') session = null;
        return send(200, { result: 'ZCFG_SUCCESS' });
      }
    }
    send(404, { result: 'not found' });
  });
}).listen(port, '127.0.0.1', () => console.log('mock on', port));
