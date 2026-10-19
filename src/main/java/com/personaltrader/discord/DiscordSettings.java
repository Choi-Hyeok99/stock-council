package com.personaltrader.discord;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.io.StringReader;
import java.util.*;

/** The private webhook never enters HTTP models, logs or database payloads. */
@Component
public class DiscordSettings {
    private final URI endpoint;
    private final String targetKey;
    private final boolean enabled;
    private final Set<String> slots;
    private final long progressMinutes;

    public DiscordSettings(@Value("${DISCORD_WEBHOOK_URL:}") String webhook,
            @Value("${discord.enabled:true}") boolean enabled,
            @Value("${discord.report-slots:MORNING}") String reportSlots,
            @Value("${discord.progress-minutes:15}") long progressMinutes) {
        this.enabled=enabled;
        this.progressMinutes=Math.max(5,Math.min(240,progressMinutes));
        Set<String> requested=new LinkedHashSet<>();
        for(String slot:reportSlots.split(",")) {
            String value=slot.strip().toUpperCase(Locale.ROOT);
            if(!Set.of("MORNING","MIDDAY","CLOSE").contains(value))
                throw new IllegalArgumentException("Discord 보고 구간 설정을 확인해 주세요.");
            requested.add(value);
        }
        this.slots=Collections.unmodifiableSet(requested);
        URI loaded=null; String key=null;
        try {
            Path file=Path.of(".env.discord").toAbsolutePath();
            if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)) {
                if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>4096)
                    throw new IllegalStateException("Discord 설정 파일 확인 필요");
                Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
                Properties props=new Properties();
                props.load(new StringReader(Files.readString(file,StandardCharsets.UTF_8)));
                webhook=props.getProperty("DISCORD_WEBHOOK_URL","").strip();
            }
            if(webhook.matches("https://discord\\.com/api/webhooks/[0-9]{17,20}/[A-Za-z0-9_-]{40,200}")) {
                loaded=URI.create(webhook+"?wait=true");
                key=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(webhook.getBytes(StandardCharsets.UTF_8)));
            }
        } catch(Exception ignored) { /* never expose URL or parsing exceptions */ }
        this.endpoint=loaded; this.targetKey=key;
    }
    public boolean configured(){return endpoint!=null;}
    public boolean enabled(){return enabled&&configured();}
    URI endpoint(){return endpoint;}
    String targetKey(){return targetKey;}
    public Set<String> slots(){return slots;}
    public long progressMinutes(){return progressMinutes;}
}
