package io.paysettle.fraud;

import io.paysettle.config.PaySettleProperties;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * OFAC-style name screening of wire parties with Jaro-Winkler similarity on normalised names (case, accents,
 * punctuation and common legal suffixes removed). A potential match holds the wire for compliance review; it
 * is never auto-released.
 */
@Component
public class SanctionsScreener {

    private static final Set<String> NOISE = Set.of("llc", "inc", "ltd", "limited", "co", "corp", "corporation", "the", "sa", "gmbh", "plc");

    public record Hit(String partyName, String listName, String program, double similarity) {
    }

    private final JdbcClient jdbc;
    private final double threshold;
    private volatile List<String[]> cache = List.of();
    private volatile Instant loadedAt = Instant.EPOCH;

    public SanctionsScreener(JdbcClient jdbc, PaySettleProperties props) {
        this.jdbc = jdbc;
        this.threshold = props.fraud().sanctionsMatchThreshold();
    }

    public Optional<Hit> screen(String... partyNames) {
        refresh();
        Hit best = null;
        for (String party : partyNames) {
            String p = normalize(party);
            if (p.isEmpty()) {
                continue;
            }
            for (String[] e : cache) {
                double sim = jaroWinkler(p, e[2]);
                if (sim >= threshold && (best == null || sim > best.similarity())) {
                    best = new Hit(party, e[0], e[1], Math.round(sim * 1000) / 1000.0);
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private void refresh() {
        if (Duration.between(loadedAt, Instant.now()).toMinutes() < 10 && !cache.isEmpty()) {
            return;
        }
        cache = jdbc.sql("SELECT name, program FROM sanctions_entries").query((rs, i) ->
                new String[] {rs.getString(1), rs.getString(2), normalize(rs.getString(1))}).list();
        loadedAt = Instant.now();
    }

    static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String s = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ");
        StringBuilder out = new StringBuilder();
        for (String token : s.trim().split("\\s+")) {
            if (!token.isEmpty() && !NOISE.contains(token)) {
                out.append(out.isEmpty() ? "" : " ").append(token);
            }
        }
        return out.toString();
    }

    static double jaroWinkler(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        int range = Math.max(0, Math.max(a.length(), b.length()) / 2 - 1);
        boolean[] am = new boolean[a.length()];
        boolean[] bm = new boolean[b.length()];
        int matches = 0;
        for (int i = 0; i < a.length(); i++) {
            for (int j = Math.max(0, i - range); j < Math.min(b.length(), i + range + 1); j++) {
                if (!bm[j] && a.charAt(i) == b.charAt(j)) {
                    am[i] = bm[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0;
        }
        int t = 0;
        int k = 0;
        for (int i = 0; i < a.length(); i++) {
            if (am[i]) {
                while (!bm[k]) {
                    k++;
                }
                if (a.charAt(i) != b.charAt(k)) {
                    t++;
                }
                k++;
            }
        }
        double m = matches;
        double jaro = (m / a.length() + m / b.length() + (m - t / 2.0) / m) / 3.0;
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(a.length(), b.length())) && a.charAt(i) == b.charAt(i); i++) {
            prefix++;
        }
        return jaro + prefix * 0.1 * (1 - jaro);
    }
}
