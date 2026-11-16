package db.mysql;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Assign a fresh per-account session version to pre-existing users. */
public class V3__security_version extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE users ADD COLUMN security_version VARCHAR(36)");
        }
        try (var query = connection.prepareStatement("SELECT id FROM users");
             var rows = query.executeQuery();
             PreparedStatement update = connection.prepareStatement("UPDATE users SET security_version=? WHERE id=?")) {
            while (rows.next()) {
                update.setString(1, UUID.randomUUID().toString());
                update.setLong(2, rows.getLong(1));
                update.executeUpdate();
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE users MODIFY COLUMN security_version VARCHAR(36) NOT NULL");
        }
    }
}
