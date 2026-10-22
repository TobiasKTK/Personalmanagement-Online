package de.pemonline.mandant;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration
public class MandantenKonfiguration {
    @Bean @FlywayDataSource
    HikariDataSource basisDataSource(@Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username:sa}") String name, @Value("${spring.datasource.password:}") String passwort,
            @Value("${spring.datasource.hikari.maximum-pool-size:5}") int poolgroesse) {
        var ds = new HikariDataSource();
        ds.setJdbcUrl(url); ds.setUsername(name); ds.setPassword(passwort); ds.setMaximumPoolSize(poolgroesse);
        // Hikari setzt das Schema beim Zurückgeben einer Verbindung wieder auf PUBLIC zurück.
        ds.setSchema("PUBLIC");
        return ds;
    }
    @Bean @Primary DataSource dataSource(@Qualifier("basisDataSource") DataSource basis) { return new MandantenDatenquelle(basis); }
    @Bean @Primary JdbcClient jdbcClient(DataSource dataSource) { return JdbcClient.create(dataSource); }
    @Bean JdbcClient verwaltungJdbc(@Qualifier("basisDataSource") DataSource basis) { return JdbcClient.create(basis); }
    @Bean FlywayMigrationStrategy mandantenMigration(@Qualifier("basisDataSource") DataSource basis) {
        return flyway -> {
            flyway.migrate();
            verwaltungMigrieren(basis);
            var ids = JdbcClient.create(basis).sql("SELECT id FROM VERWALTUNG.mandantenspeicher WHERE id <> 1 AND status = 'AKTIV'").query(Long.class).list();
            ids.forEach(id -> fachschemaMigrieren(basis, id));
        };
    }
    public static void verwaltungMigrieren(DataSource basis) {
        Flyway.configure().dataSource(basis).schemas("VERWALTUNG").defaultSchema("VERWALTUNG")
            .locations("classpath:db/verwaltung").load().migrate();
    }
    public static void fachschemaMigrieren(DataSource basis, long id) {
        String schema = MandantenKontext.schema(id);
        Flyway.configure().dataSource(basis).schemas(schema).defaultSchema(schema)
            .locations("classpath:db/migration").load().migrate();
    }
}
