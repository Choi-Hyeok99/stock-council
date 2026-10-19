package com.personaltrader.broker;

import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;

/** Account identifiers are kept in a private, Git-excluded file and never returned to the UI. */
@Component
public class KisPaperAccountSettings {
    private final Path file = Path.of(".env.kis-account").toAbsolutePath();
    private Credentials credentials;

    public KisPaperAccountSettings() {
        try {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) > 1024) return;
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            Properties props = new Properties();
            props.load(new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
            String number = props.getProperty("KIS_PAPER_ACCOUNT_NUMBER", "").strip();
            String product = props.getProperty("KIS_PAPER_ACCOUNT_PRODUCT", "").strip();
            if (valid(number, product)) credentials = new Credentials(number, product);
        } catch (IOException | RuntimeException ignored) { /* no file contents or identifiers in logs */ }
    }

    public synchronized boolean configured() { return credentials != null; }
    public synchronized Credentials credentials() { return credentials; }

    private boolean valid(String number, String product) {
        return number != null && product != null && number.matches("[0-9]{8}") && product.matches("[0-9]{2}");
    }

    // Deliberately not a record: implicit toString must never expose credentials.
    public static final class Credentials {
        private final String number;
        private final String product;
        private Credentials(String number, String product) { this.number = number; this.product = product; }
        public String number() { return number; }
        public String product() { return product; }
        @Override public String toString() { return "KisPaperAccountCredentials[REDACTED]"; }
    }
}
