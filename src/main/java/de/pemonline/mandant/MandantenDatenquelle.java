package de.pemonline.mandant;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Jede fachliche Transaktion verwendet ausschließlich das Schema ihres Mandanten. */
public final class MandantenDatenquelle extends AbstractDataSource {
    private final DataSource basis;
    public MandantenDatenquelle(DataSource basis) { this.basis = basis; }
    @Override public Connection getConnection() throws SQLException { return vorbereiten(basis.getConnection()); }
    @Override public Connection getConnection(String name, String passwort) throws SQLException { return vorbereiten(basis.getConnection(name, passwort)); }
    private Connection vorbereiten(Connection c) throws SQLException {
        try {
            c.setSchema(MandantenKontext.schema(MandantenKontext.id()));
            return c;
        } catch (RuntimeException | SQLException e) {
            c.close();
            throw e;
        }
    }
}
