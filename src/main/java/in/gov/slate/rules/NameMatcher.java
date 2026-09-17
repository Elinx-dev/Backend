package in.gov.slate.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Normalises only spacing, capitalisation and punctuation. Reordered names,
 * initials and abbreviations are never automatically treated as the same person:
 * they come back as POSSIBLE_NAME_MATCH for an officer to decide.
 */
public final class NameMatcher {

    public enum Decision { MATCH, POSSIBLE_NAME_MATCH, NO_MATCH }

    public record Result(Decision decision, String normalizedLeft, String normalizedRight, String rationale) {
    }

    private NameMatcher() {
    }

    public static Result compare(String left, String right) {
        String nl = normalize(left);
        String nr = normalize(right);
        if (nl.isEmpty() || nr.isEmpty()) {
            return new Result(Decision.NO_MATCH, nl, nr, "One of the names is empty after normalisation");
        }
        if (nl.equals(nr)) {
            return new Result(Decision.MATCH, nl, nr, "Exact match after formatting normalisation");
        }
        List<String> lt = tokens(nl);
        List<String> rt = tokens(nr);

        List<String> ls = new ArrayList<>(lt);
        List<String> rs = new ArrayList<>(rt);
        java.util.Collections.sort(ls);
        java.util.Collections.sort(rs);
        if (ls.equals(rs)) {
            return new Result(Decision.POSSIBLE_NAME_MATCH, nl, nr,
                    "Same name tokens in a different order; identity not automatically equated");
        }
        if (initialsCompatible(lt, rt) || initialsCompatible(rt, lt)) {
            return new Result(Decision.POSSIBLE_NAME_MATCH, nl, nr,
                    "One name uses an initial or abbreviation of the other");
        }
        if (!java.util.Collections.disjoint(lt, rt)) {
            return new Result(Decision.POSSIBLE_NAME_MATCH, nl, nr,
                    "Names share a token but differ elsewhere");
        }
        return new Result(Decision.NO_MATCH, nl, nr, "No shared name tokens");
    }

    /** Every token on the short side is either present or an initial of a token on the long side. */
    private static boolean initialsCompatible(List<String> shortSide, List<String> longSide) {
        if (shortSide.size() > longSide.size()) {
            return false;
        }
        List<String> remaining = new ArrayList<>(longSide);
        for (String token : shortSide) {
            String consumed = null;
            for (String candidate : remaining) {
                if (candidate.equals(token)
                        || (token.length() == 1 && candidate.startsWith(token))
                        || (candidate.length() == 1 && token.startsWith(candidate))) {
                    consumed = candidate;
                    break;
                }
            }
            if (consumed == null) {
                return false;
            }
            remaining.remove(consumed);
        }
        return true;
    }

    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        return name.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static List<String> tokens(String normalized) {
        return normalized.isEmpty() ? List.of() : List.of(normalized.split(" "));
    }
}
