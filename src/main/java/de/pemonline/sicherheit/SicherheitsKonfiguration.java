package de.pemonline.sicherheit;

import de.pemonline.anmeldung.AnmeldungFehlerHandler;
import de.pemonline.mandant.Anmeldekonten;
import de.pemonline.mandant.MandantenFilter;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import tools.jackson.databind.ObjectMapper;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SicherheitsKonfiguration {
    @Bean HttpSessionEventPublisher sessionEvents() { return new HttpSessionEventPublisher(); }
    @Bean SecurityFilterChain security(HttpSecurity http, SessionRegistry sessions, Anmeldekonten anmeldekonten,
            Anmeldeart art, ObjectProvider<ClientRegistrationRepository> registrierungen,
            ObjectProvider<KeycloakBenutzerService> oidcBenutzer, ObjectMapper json) throws Exception {
        http.csrf(csrf -> csrf.spa())
                .addFilterAfter(new MandantenFilter(anmeldekonten), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/error", "/oauth2/**", "/login/oauth2/**", "/passwort-vergessen", "/passwort-zuruecksetzen", "/email-bestaetigen").permitAll()
                        .requestMatchers(HttpMethod.GET, "/anmeldung/login.css", "/anmeldung/login.js", "/anmeldung/keycloak.css", "/anmeldung/design-tokens.css").permitAll()
                        .requestMatchers("/api/mandantenverwaltung/**").hasRole("SYSTEMADMIN")
                        .requestMatchers("/api/mandanten", "/api/mandanten/**", "/api/sitzung").authenticated()
                        .requestMatchers("/api/konten/**").hasRole("ADMIN")
                        .requestMatchers("/api/entwicklung/**").hasAnyRole("PERSONAL", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/organisationseinheiten/**").hasAnyRole("LESEN", "PERSONAL", "ADMIN")
                        .requestMatchers("/api/vertretungen/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/feiertagskalender").hasAnyRole("PERSONAL", "ADMIN")
                        .requestMatchers("/api/feiertagskalender/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/besetzungsregeln").hasAnyRole("PERSONAL", "ADMIN")
                        .requestMatchers("/api/besetzungsregeln/**").hasRole("ADMIN")
                        .requestMatchers("/api/benachrichtigungen/**").hasAnyRole("LESEN", "PERSONAL", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/dashboard").hasAnyRole("LESEN", "PERSONAL", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/export/**").hasAnyRole("PERSONAL", "ADMIN")
                        .requestMatchers("/api/mitarbeiter/*/urlaubsfreigabe").hasRole("ADMIN")
                        .requestMatchers("/api/urlaub/**").hasAnyRole("LESEN", "PERSONAL", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/kalender").hasAnyRole("LESEN", "PERSONAL", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/mitarbeiter/*/verlauf").hasAnyRole("PERSONAL", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/sitzung", "/api/mitarbeiter/**", "/api/statistik", "/api/organisationseinheiten").hasAnyRole("LESEN", "PERSONAL", "ADMIN")
                        .requestMatchers("/api/organisationseinheiten/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/mitarbeiter/**").hasAnyRole("PERSONAL", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/mitarbeiter/**").hasAnyRole("PERSONAL", "ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/mitarbeiter/**").hasAnyRole("PERSONAL", "ADMIN")
                        .requestMatchers("/api/**").denyAll()
                        .anyRequest().authenticated())
                .requestCache(cache -> cache.disable())
                .exceptionHandling(errors -> errors
                        .defaultAuthenticationEntryPointFor((request, response, e) -> {
                            response.setStatus(401); response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"meldung\":\"Bitte anmelden.\"}");
                        }, PathPatternRequestMatcher.withDefaults().matcher("/api/**"))
                        .accessDeniedHandler((request, response, e) -> {
                            if (request.getRequestURI().equals("/login")) {
                                response.sendRedirect("/login?expired");
                                return;
                            }
                            response.setStatus(403); response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"meldung\":\"Keine Berechtigung oder ungültiger Sicherheitsnachweis. Bitte bei Bedarf neu anmelden.\"}");
                        }))
                .sessionManagement(session -> session.maximumSessions(10).sessionRegistry(sessions)
                        .expiredSessionStrategy(event -> {
                            if (!event.getRequest().getRequestURI().startsWith("/api/")) {
                                event.getResponse().sendRedirect("/login");
                                return;
                            }
                            event.getResponse().setStatus(401);
                            event.getResponse().setContentType("application/json;charset=UTF-8");
                            event.getResponse().getWriter().write("{\"meldung\":\"Sitzung abgelaufen. Bitte neu anmelden.\"}");
                        }));
        if (art.keycloak()) {
            var clients = registrierungen.getObject();
            var resolver = new DefaultOAuth2AuthorizationRequestResolver(clients, "/oauth2/authorization");
            resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
            var logout = new OidcClientInitiatedLogoutSuccessHandler(clients);
            logout.setPostLogoutRedirectUri("{baseUrl}/login?abgemeldet");
            logout.setDefaultTargetUrl("/login?abgemeldet");
            // XHR-Redirects zum IdP scheitern an der Origin-Grenze. Der Browser navigiert selbst.
            logout.setRedirectStrategy((request, response, url) -> {
                response.setContentType("application/json;charset=UTF-8");
                response.setHeader("Cache-Control", "no-store");
                response.getWriter().write(json.writeValueAsString(java.util.Map.of("weiterleitung", url)));
            });
            http.oauth2Login(oauth -> oauth.loginPage("/login").defaultSuccessUrl("/", true)
                    .failureUrl("/login?error")
                    .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(resolver))
                    .userInfoEndpoint(info -> info.oidcUserService(oidcBenutzer.getObject())))
                .logout(config -> config.logoutSuccessHandler(logout));
        } else {
            http.formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/", true)
                    .failureHandler(new AnmeldungFehlerHandler()))
                .logout(logout -> logout.logoutSuccessHandler((request, response, auth) -> response.setStatus(204)));
        }
        return http.build();
    }
}
