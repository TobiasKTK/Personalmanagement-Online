package de.pemonline.anmeldung;

import java.io.IOException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;

public class AnmeldungFehlerHandler extends SimpleUrlAuthenticationFailureHandler {
    static final String KONTONAME = AnmeldungFehlerHandler.class.getName() + ".kontoname";

    public AnmeldungFehlerHandler() {
        super("/login?error");
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException, ServletException {
        String name = request.getParameter("username");
        name = name == null ? "" : name.strip();
        // Nur den Kontonamen für die nächste Formularanzeige aufbewahren, begrenzt wie das Eingabefeld.
        request.getSession().setAttribute(KONTONAME, name.substring(0, Math.min(name.length(), 254)));
        super.onAuthenticationFailure(request, response, exception);
    }
}
