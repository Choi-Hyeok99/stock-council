package com.personaltrader.broker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.io.StringReader;
import java.util.Properties;

/** LIVE credentials have no PAPER fallback. Loading credentials does not arm trading. */
@Component
@DependsOnDatabaseInitialization
public class KisExecutionSettings {
    private final Credentials live;
    private final byte[] identitySalt;

    public KisExecutionSettings(JdbcTemplate jdbc,
            @Value("${KIS_LIVE_APP_KEY:}") String key, @Value("${KIS_LIVE_APP_SECRET:}") String secret,
            @Value("${KIS_LIVE_ACCOUNT_NUMBER:}") String number, @Value("${KIS_LIVE_ACCOUNT_PRODUCT:}") String product) {
        this.identitySalt = loadIdentitySalt(jdbc);
        Credentials loaded = null;
        Path file = Path.of(".env.kis-live").toAbsolutePath();
        try {
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 4096)
                    throw new IllegalStateException("실전 설정 파일 확인 필요");
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
                Properties props = new Properties();
                props.load(new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
                // A present file is authoritative: invalid/incomplete contents must not fall back to another account.
                key = props.getProperty("KIS_LIVE_APP_KEY", "").strip();
                secret = props.getProperty("KIS_LIVE_APP_SECRET", "").strip();
                number = props.getProperty("KIS_LIVE_ACCOUNT_NUMBER", "").strip();
                product = props.getProperty("KIS_LIVE_ACCOUNT_PRODUCT", "").strip();
            }
            if (valid(key, secret, number, product)) loaded = new Credentials(key, secret, number, product);
        } catch (Exception ignored) { /* no secret values or underlying exception details are exposed */ }
        this.live = loaded;
    }

    private static boolean valid(String key, String secret, String number, String product) {
        return key != null && secret != null && number != null && product != null
                && key.matches("[A-Za-z0-9_+/=-]{16,512}") && secret.matches("[A-Za-z0-9_+/=-]{16,1024}")
                && number.matches("[0-9]{8}") && product.matches("[0-9]{2}");
    }

    public boolean configured() { return live != null; }
    public boolean identityAvailable() { return identitySalt != null; }
    public Credentials credentials() { return live; }

    /** Stable through API credential rotation; retain this private file together with the order database. */
    public String accountFingerprint(ExecutionBrokerClient.Environment environment, String number, String product) {
        if (identitySalt == null || environment == null || !number.matches("[0-9]{8}") || !product.matches("[0-9]{2}"))
            throw new IllegalStateException("계좌 식별 설정 확인 필요");
        try {
            javax.crypto.Mac hmac = javax.crypto.Mac.getInstance("HmacSHA256");
            hmac.init(new javax.crypto.spec.SecretKeySpec(identitySalt, "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(hmac.doFinal((environment.name() + ":" + number + ":" + product)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignored) { throw new IllegalStateException("계좌 식별 확인 실패"); }
    }

    private static byte[] loadIdentitySalt(JdbcTemplate jdbc) {
        Path file = Path.of(".env.execution-salt").toAbsolutePath();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            Long accounts = jdbc.queryForObject("SELECT COUNT(*) FROM execution_account", Long.class);
            if (accounts == null || accounts > 0)
                throw new IllegalStateException("기존 주문 원장이 있습니다. 백업한 계좌 식별 salt를 복구한 뒤 시작해 주세요.");
        }
        try {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] random = new byte[32]; new java.security.SecureRandom().nextBytes(random);
                byte[] encoded = (java.util.HexFormat.of().formatHex(random) + "\n").getBytes(StandardCharsets.UTF_8);
                Path temporary = Files.createTempFile(file.getParent(), ".env.execution-salt-", ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                try {
                    try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(temporary,
                            StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(encoded);
                        while (buffer.hasRemaining()) channel.write(buffer);
                        channel.force(true);
                    }
                    // Linking a complete private inode publishes atomically and cannot replace an existing salt.
                    try { Files.createLink(file, temporary); }
                    catch (FileAlreadyExistsException concurrent) { /* another process won; verify its complete file below */ }
                } finally { Files.deleteIfExists(temporary); }
            }
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 128) return null;
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            String value = Files.readString(file, StandardCharsets.UTF_8).strip();
            return value.matches("[0-9a-f]{64}") ? java.util.HexFormat.of().parseHex(value) : null;
        } catch (Exception ignored) { return null; }
    }

    public static final class Credentials {
        private final String key, secret, number, product;
        private Credentials(String key, String secret, String number, String product) {
            this.key = key; this.secret = secret; this.number = number; this.product = product;
        }
        public String key() { return key; }
        public String secret() { return secret; }
        public String number() { return number; }
        public String product() { return product; }
        @Override public String toString() { return "KisLiveCredentials[REDACTED]"; }
    }
}
