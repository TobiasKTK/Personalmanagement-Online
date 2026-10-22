package de.pemonline.sicherheit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KontenMailTest {
    @Test void festeHttpsLinksUndKeinePasswoerterWerdenVersendet() {
        var factory = new DefaultListableBeanFactory();
        var sender = mock(JavaMailSender.class); factory.registerSingleton("sender", sender);
        var mail = new KontenMail(factory.getBeanProvider(JavaMailSender.class), true, "pem@example.org", "https://pem.example.org/");
        mail.link("person@example.org", "PASSWORT", "beispieltoken");
        var captor = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        assertThat(captor.getValue().getTo()).containsExactly("person@example.org");
        assertThat(captor.getValue().getFrom()).isEqualTo("pem@example.org");
        assertThat(captor.getValue().getText()).contains("https://pem.example.org/passwort-zuruecksetzen?token=beispieltoken", "30 Minuten");
    }
    @Test void unsichereOderMehrdeutigeBasisUrlsWerdenAbgewiesen() {
        var factory = new DefaultListableBeanFactory(); factory.registerSingleton("sender", mock(JavaMailSender.class));
        for (String url : new String[] {"http://pem.example.org", "https://pem.example.org/pfad", "https://pem.example.org?ziel=evil", "https://user:pass@pem.example.org", "https://pem.example.org#fragment"})
            assertThatThrownBy(() -> new KontenMail(factory.getBeanProvider(JavaMailSender.class), true, "pem@example.org", url)).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new KontenMail(factory.getBeanProvider(JavaMailSender.class), true, "pem@example.org", "http://127.0.0.1:18091")).doesNotThrowAnyException();
        assertThat(new KontenMail(new DefaultListableBeanFactory().getBeanProvider(JavaMailSender.class), false, "", "").aktiv()).isFalse();
    }
    @Test void normalisierungLaesstPlusUndPunktAliasUnveraendertUndVerhindertHeaderInjection() {
        assertThat(EmailWiederherstellung.emailNormalisieren("  Vor.Name+Pem@EXAMPLE.ORG  ")).isEqualTo("vor.name+pem@example.org");
        for (String adresse : new String[] {"a@example.org\r\nBcc: x@example.org", "a..b@example.org", "a@-example.org", "a@", "a b@example.org", "ä@example.org"})
            assertThatThrownBy(() -> EmailWiederherstellung.emailNormalisieren(adresse)).isInstanceOf(IllegalArgumentException.class);
    }
}
