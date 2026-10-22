package de.pemonline.fachlich;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@Configuration
public class ZeitKonfiguration {
    @Bean Clock clock() { return Clock.system(ZoneId.of("Europe/Berlin")); }
    @Bean @Primary LocalValidatorFactoryBean validator(Clock clock) {
        var validator = new LocalValidatorFactoryBean();
        validator.setConfigurationInitializer(config -> config.clockProvider(() -> clock));
        return validator;
    }
}
