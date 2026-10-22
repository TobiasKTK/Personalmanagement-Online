package de.pemonline;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.json.JsonMapper;

/** Isolierter OIDC-Provider: echter Code-Austausch, PKCE und RSA-signierte ID-Tokens. */
final class TestOidcServer implements AutoCloseable {
    private final HttpServer server;
    private final RSAKey key;
    private final RSAKey fremderKey;
    private final Map<String, Map<String, String>> codes = new ConcurrentHashMap<>();
    private final JsonMapper json = JsonMapper.builder().build();
    volatile String konto = "testadmin";
    volatile String tokenFehler = "";
    volatile boolean pkceGeprueft;

    TestOidcServer() {
        try {
            key = new RSAKeyGenerator(2048).keyID("test-key").generate();
            fremderKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::antworten);
            server.start();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    String issuer() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/test"; }

    private void antworten(HttpExchange exchange) throws IOException {
        try {
            String pfad = exchange.getRequestURI().getPath();
            if (pfad.endsWith("/.well-known/openid-configuration")) {
                var metadata = new LinkedHashMap<String, Object>();
                metadata.put("issuer", issuer());
                metadata.put("authorization_endpoint", issuer() + "/auth");
                metadata.put("token_endpoint", issuer() + "/token");
                metadata.put("jwks_uri", issuer() + "/certs");
                metadata.put("userinfo_endpoint", issuer() + "/userinfo");
                metadata.put("end_session_endpoint", issuer() + "/logout");
                metadata.put("response_types_supported", List.of("code"));
                metadata.put("subject_types_supported", List.of("public"));
                metadata.put("id_token_signing_alg_values_supported", List.of("RS256"));
                metadata.put("token_endpoint_auth_methods_supported", List.of("none", "client_secret_basic"));
                senden(exchange, 200, metadata);
            } else if (pfad.endsWith("/certs")) {
                senden(exchange, 200, new JWKSet(key.toPublicJWK()).toJSONObject());
            } else if (pfad.endsWith("/auth")) {
                var parameter = parameter(exchange.getRequestURI().getRawQuery());
                String code = UUID.randomUUID().toString();
                codes.put(code, parameter);
                exchange.getResponseHeaders().set("Location", parameter.get("redirect_uri") + "?code=" + code + "&state=" + parameter.get("state"));
                exchange.sendResponseHeaders(302, -1);
            } else if (pfad.endsWith("/token")) {
                var parameter = parameter(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                var anfrage = codes.remove(parameter.get("code"));
                if (anfrage == null) { senden(exchange, 400, Map.of("error", "invalid_grant")); return; }
                String verifier = parameter.getOrDefault("code_verifier", "");
                String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
                if (!"S256".equals(anfrage.get("code_challenge_method")) || !challenge.equals(anfrage.get("code_challenge"))) {
                    senden(exchange, 400, Map.of("error", "invalid_grant")); return;
                }
                pkceGeprueft = true;
                var claims = new JWTClaimsSet.Builder().issuer(tokenFehler.equals("issuer") ? issuer() + "-falsch" : issuer())
                        .subject("keycloak-subjekt-123").audience(tokenFehler.equals("audience") ? "fremder-client" : "pemonline")
                        .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                        .claim("nonce", tokenFehler.equals("nonce") ? "falsch" : anfrage.get("nonce"))
                        .claim("preferred_username", "nicht-der-lokale-kontoname")
                        .claim("realm_access", Map.of("roles", List.of("ADMIN", "SYSTEMADMIN")));
                if (konto != null) claims.claim("pemonline_konto", konto);
                var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
                jwt.sign(new RSASSASigner(tokenFehler.equals("signatur") ? fremderKey : key));
                senden(exchange, 200, Map.of("access_token", "test-access", "token_type", "Bearer", "expires_in", 300,
                        "scope", "openid profile email", "id_token", jwt.serialize()));
            } else if (pfad.endsWith("/userinfo")) {
                // Eine fremde, nicht signierte Kontozuordnung darf den ID-Token nicht überschreiben.
                senden(exchange, 200, Map.of("sub", "keycloak-subjekt-123", "pemonline_konto", "fremdes-konto"));
            } else senden(exchange, 404, Map.of("error", "not_found"));
        } catch (Exception e) {
            senden(exchange, 500, Map.of("error", "test_provider_error"));
        } finally { exchange.close(); }
    }

    private void senden(HttpExchange exchange, int status, Object inhalt) throws IOException {
        byte[] bytes = json.writeValueAsBytes(inhalt);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    static Map<String, String> parameter(String query) {
        var ergebnis = new HashMap<String, String>();
        for (String teil : query.split("&")) {
            String[] paar = teil.split("=", 2);
            ergebnis.put(URLDecoder.decode(paar[0], StandardCharsets.UTF_8), paar.length == 2
                    ? URLDecoder.decode(paar[1], StandardCharsets.UTF_8) : "");
        }
        return ergebnis;
    }

    @Override public void close() { server.stop(0); }
}
