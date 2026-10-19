package com.personaltrader.committee;

import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;

/** Reads only this project's owner-private file. Never exposes or logs its contents. */
@Component
public class OpenAiCredentials {
    public static final class Secret {
        private final String key;private final boolean enabled;
        private Secret(String key,boolean enabled){this.key=key;this.enabled=enabled;}
        String key(){return key;}public boolean enabled(){return enabled;}
        @Override public String toString(){return "OpenAI credential [redacted]";}
    }
    public Optional<Secret> read(){
        try{
            Path path=Path.of(".env.openai");
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>4096)return Optional.empty();
            Set<PosixFilePermission> permissions=Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS);
            if(permissions.stream().anyMatch(p->p.name().startsWith("GROUP_")||p.name().startsWith("OTHERS_")))return Optional.empty();
            if(!Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).getName().equals(System.getProperty("user.name")))return Optional.empty();
            Map<String,String> values=new HashMap<>();
            for(String line:Files.readAllLines(path)){int at=line.indexOf('=');if(at>0)values.put(line.substring(0,at),line.substring(at+1));}
            String key=values.get("OPENAI_API_KEY");if(key==null||!key.matches("sk-[A-Za-z0-9_-]{16,500}"))return Optional.empty();
            return Optional.of(new Secret(key,"true".equals(values.get("OPENAI_COMMITTEE_ENABLED"))&&"true".equals(values.get("OPENAI_PROJECT_HARD_LIMIT_CONFIRMED"))));
        }catch(Exception failure){return Optional.empty();}
    }
    public boolean available(){return read().filter(Secret::enabled).isPresent();}
}
