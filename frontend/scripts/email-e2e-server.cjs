'use strict';
// Ausschließlich isolierter Browsertest: SMTP fängt Nachrichten lokal ab, kein externer Versand.
const net = require('node:net');
const http = require('node:http');
const { spawn } = require('node:child_process');
const nachrichten = [];
const smtp = net.createServer(socket => {
  socket.setEncoding('utf8');
  socket.write('220 localhost Test SMTP\r\n');
  let puffer = '', daten = false, zeilen = [], empfaenger = '';
  socket.on('data', chunk => {
    puffer += chunk;
    let ende;
    while ((ende = puffer.indexOf('\r\n')) !== -1) {
      const zeile = puffer.slice(0, ende); puffer = puffer.slice(ende + 2);
      if (daten) {
        if (zeile !== '.') { zeilen.push(zeile.startsWith('..') ? zeile.slice(1) : zeile); continue; }
        const raw = zeilen.join('\r\n');
        const trennung = raw.indexOf('\r\n\r\n');
        const header = raw.slice(0, trennung);
        let text = raw.slice(trennung + 4);
        if (/Content-Transfer-Encoding: base64/i.test(header)) text = Buffer.from(text, 'base64').toString('utf8');
        if (/Content-Transfer-Encoding: quoted-printable/i.test(header)) {
          text = text.replace(/=\r\n/g, '').replace(/=([0-9A-F]{2})/gi, (_, code) => String.fromCharCode(parseInt(code, 16)));
          text = Buffer.from(text, 'latin1').toString('utf8');
        }
        nachrichten.push({ empfaenger, text });
        if (nachrichten.length > 50) nachrichten.shift();
        daten = false; zeilen = []; socket.write('250 accepted\r\n');
      } else if (/^EHLO|^HELO/i.test(zeile)) socket.write('250 localhost\r\n');
      else if (/^RCPT TO:/i.test(zeile)) { empfaenger = zeile.slice(8).replace(/[<>]/g, '').trim(); socket.write('250 OK\r\n'); }
      else if (/^DATA$/i.test(zeile)) { daten = true; socket.write('354 end with dot\r\n'); }
      else if (/^QUIT$/i.test(zeile)) socket.end('221 bye\r\n');
      else socket.write('250 OK\r\n');
    }
  });
  socket.on('error', () => {});
});
const inbox = http.createServer((req, res) => {
  res.setHeader('Content-Type', 'application/json');
  res.end(JSON.stringify(nachrichten));
});
async function starten(server, port) {
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', resolve); });
}
(async () => {
  await starten(smtp, 18092); await starten(inbox, 18093);
  const java = spawn('java', ['-Dfile.encoding=UTF-8', '-jar', process.env.PEM_TEST_JAR ?? '../target/start/pemonline-1.0.0.jar',
    '--server.address=127.0.0.1', '--server.port=18091', '--spring.datasource.url=jdbc:h2:mem:email-e2e',
    '--pem.anmeldung=lokal',
    '--spring.datasource.username=sa', '--spring.datasource.password=', '--mitarbeiter.externer-dienst-url=',
    '--mitarbeiter.admin-name=e2eadmin', '--mitarbeiter.admin-passwort=Isolierte-Browsertests-2026!',
    '--pem.konto-mail.aktiv=true', '--pem.konto-mail.absender=pem@example.org', '--pem.konto-mail.basis-url=http://127.0.0.1:18091',
    '--spring.mail.host=127.0.0.1', '--spring.mail.port=18092', '--spring.mail.username=', '--spring.mail.password=',
    '--spring.mail.properties.mail.smtp.auth=false', '--spring.mail.properties.mail.smtp.starttls.required=false',
    '--spring.mail.properties.mail.smtp.ssl.enable=false', '--logging.file.name=../target/test-logs/email-e2e.log'],
    { stdio: 'inherit', windowsHide: true });
  java.on('exit', code => { smtp.close(); inbox.close(); process.exit(code ?? 0); });
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { java.kill(); smtp.close(); inbox.close(); });
})().catch(e => { console.error(e.message); process.exit(1); });
