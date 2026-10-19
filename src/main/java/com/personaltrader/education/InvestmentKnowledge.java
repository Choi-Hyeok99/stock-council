package com.personaltrader.education;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/** Curated, packaged reference material. Never downloads sources or executes text as instructions. */
@Service
public class InvestmentKnowledge {
    private final Curriculum curriculum;
    private final String contentHash;
    public InvestmentKnowledge(ObjectMapper mapper) {
        try (var input = new ClassPathResource("knowledge/investment-curriculum.json").getInputStream()) {
            byte[] raw = input.readAllBytes();
            curriculum = mapper.readValue(raw, Curriculum.class);
            validate(curriculum);
            contentHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (Exception ignored) { throw new IllegalStateException("투자 교육 기준을 읽거나 검증하지 못했습니다. 시작을 중단합니다."); }
    }

    private static void validate(Curriculum data) {
        Objects.requireNonNull(data.version()); LocalDate.parse(data.reviewedAt());
        if (!data.mode().equals("REFERENCE_AND_RULE_REVIEW")) throw new IllegalArgumentException();
        Set<String> allowed = Set.of("www.berkshirehathaway.com", "www.williamoneil.com", "www.oaktreecapital.com",
                "corporate.vanguard.com", "www.aqr.com", "www.investor.gov", "opendart.fss.or.kr", "pages.stern.nyu.edu");
        Set<String> sources = new HashSet<>(), principles = new HashSet<>(), terms = new HashSet<>(), roles = new HashSet<>();
        for (Source source : data.sources()) {
            URI uri = URI.create(source.url());
            if (!"https".equals(uri.getScheme()) || !allowed.contains(uri.getHost()) || uri.getUserInfo() != null
                    || uri.getPort() != -1 || !sources.add(source.id())) throw new IllegalArgumentException();
        }
        for (Term term : data.terms()) {
            if (!Set.of("BASIC", "INTERMEDIATE", "ADVANCED").contains(term.level()) || !terms.add(term.name())) throw new IllegalArgumentException();
        }
        for (Principle principle : data.principles()) {
            if (!principles.add(principle.id()) || principle.sourceIds().isEmpty() || !sources.containsAll(principle.sourceIds())) throw new IllegalArgumentException();
        }
        for (Role role : data.roles()) {
            if (!roles.add(role.name()) || !principles.containsAll(role.principleIds()) || !terms.containsAll(role.termNames())) throw new IllegalArgumentException();
        }
        if (sources.size() < 3 || roles.size() < 6 || terms.size() < 20) throw new IllegalArgumentException();
    }

    public Curriculum curriculum() { return curriculum; }
    public String hash() { return contentHash; }
    public Role role(String name) {
        return curriculum.roles().stream().filter(role -> role.name().equals(name)).findFirst().orElseThrow();
    }
    public String roleBrief(String name) {
        Role role = role(name);
        return curriculum.version() + " · " + String.join(", ", role.principleIds()) + " · " + role.instruction();
    }
    public record Curriculum(String version, String reviewedAt, String mode, List<Source> sources, List<Term> terms,
                             List<Principle> principles, List<Role> roles) {}
    public record Source(String id, String publisher, String title, String url, String scope) {}
    public record Term(String level, String name, String explanation) {}
    public record Principle(String id, String title, String attribution, String application, List<String> sourceIds, String limitation) {}
    public record Role(String name, String purpose, List<String> termNames, List<String> principleIds, String instruction) {}
}
