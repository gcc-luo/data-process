package com.example.governance.bootstrap;

import java.io.Console;
import java.util.Arrays;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Run interactively after database migrations; secrets are read from the terminal without echo. */
public final class BootstrapCommand {
    public static void main(String[] args) {
        Console console = System.console();
        if (console == null || args.length != 0) throw new IllegalStateException("An interactive terminal is required; do not pass credentials as arguments");
        String url = requiredEnv("GOVERNANCE_DB_URL");
        String user = requiredEnv("GOVERNANCE_DB_USER");
        String dbPassword = requiredEnv("GOVERNANCE_DB_PASSWORD");
        String username = console.readLine("Administrator username: ");
        char[] password = console.readPassword("Administrator password: ");
        char[] confirmation = console.readPassword("Confirm password: ");
        try {
            if (password == null || confirmation == null || !Arrays.equals(password, confirmation)) throw new IllegalArgumentException("Passwords do not match");
            var ds = new DriverManagerDataSource(url, user, dbPassword);
            new BootstrapAdmin(new JdbcTemplate(ds), new BCryptPasswordEncoder(), new DataSourceTransactionManager(ds)).create(username, new String(password));
            console.printf("Administrator initialized.%n");
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (confirmation != null) Arrays.fill(confirmation, '\0');
        }
    }
    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable " + key);
        return value;
    }
}
