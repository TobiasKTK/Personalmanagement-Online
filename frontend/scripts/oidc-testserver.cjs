// Nur fuer isolierte Browsertests: kein echter Identitaetsdienst, keine Betriebsdaten.
const http = require('node:http');
const { generateKeyPairSync, randomUUID, createHash, sign } = require('node:crypto');
const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const jwk = { ...publicKey.export({ format: 'jwk' }), kid: 'browser-test', alg: 'RS256', use: 'sig' };
const issuer = 'http://127.0.0.1:18095/realms/browser-test';
const callback = 'http://127.0.0.1:18096/login/oauth2/code/keycloak';
const nachLogout = 'http://127.0.0.1:18096/login?abgemeldet';
const codes = new Map();
const base64 = value => Buffer.from(JSON.stringify(value)).toString('base64url');

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, issuer);
  const json = (status, body) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)); };
  const redirect = ziel => { res.writeHead(302, { Location: ziel }); res.end(); };
  if (url.pathname.endsWith('/.well-known/openid-configuration')) return json(200, {
    issuer, authorization_endpoint: issuer + '/auth', token_endpoint: issuer + '/token',
    jwks_uri: issuer + '/certs', userinfo_endpoint: issuer + '/userinfo', end_session_endpoint: issuer + '/logout',
    response_types_supported: ['code'], subject_types_supported: ['public'],
    id_token_signing_alg_values_supported: ['RS256'], token_endpoint_auth_methods_supported: ['none'],
  });
  if (url.pathname.endsWith('/certs')) return json(200, { keys: [jwk] });
  if (url.pathname.endsWith('/auth')) {
    if (url.searchParams.get('redirect_uri') !== callback || url.searchParams.get('code_challenge_method') !== 'S256')
      return json(400, { error: 'invalid_request' });
    const code = randomUUID(); codes.set(code, url.searchParams);
    return redirect(`${callback}?${new URLSearchParams({ code, state: url.searchParams.get('state') })}`);
  }
  if (url.pathname.endsWith('/token')) {
    let body = ''; for await (const chunk of req) body += chunk;
    const daten = new URLSearchParams(body), code = daten.get('code'), anfrage = codes.get(code);
    codes.delete(code);
    const challenge = createHash('sha256').update(daten.get('code_verifier') ?? '').digest('base64url');
    if (!anfrage || challenge !== anfrage.get('code_challenge')) return json(400, { error: 'invalid_grant' });
    const jetzt = Math.floor(Date.now() / 1000);
    const unsigned = base64({ alg: 'RS256', kid: jwk.kid }) + '.' + base64({
      iss: issuer, sub: 'browser-test-person', aud: 'pemonline', iat: jetzt, exp: jetzt + 300,
      nonce: anfrage.get('nonce'), pemonline_konto: 'e2eadmin', preferred_username: 'keycloak-testperson',
    });
    const token = unsigned + '.' + sign('RSA-SHA256', Buffer.from(unsigned), privateKey).toString('base64url');
    return json(200, { access_token: 'browser-test-token', token_type: 'Bearer', expires_in: 300,
      scope: 'openid profile email', id_token: token });
  }
  if (url.pathname.endsWith('/userinfo')) return json(200, { sub: 'browser-test-person' });
  if (url.pathname.endsWith('/logout')) {
    if (!url.searchParams.get('id_token_hint') || url.searchParams.get('post_logout_redirect_uri') !== nachLogout)
      return json(400, { error: 'invalid_logout' });
    return redirect(nachLogout);
  }
  json(404, { error: 'not_found' });
});
server.listen(18095, '127.0.0.1');
process.on('SIGTERM', () => server.close());
process.on('SIGINT', () => server.close());
