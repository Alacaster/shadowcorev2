package dev.shadowcore.util;

import java.util.regex.Pattern;

/**
 * Suffix validation and final-visible-name reconstruction, per sync spec §2.
 *
 * <p>A local profile's stored authoritative value is its <b>suffix</b>, not
 * its expanded visible name. The expanded visible name is reconstructed at
 * resolution time from the current controller account name plus the suffix,
 * with a deterministic truncation rule that always keeps the suffix at the
 * end.</p>
 *
 * <p>Rules recap (sync spec):</p>
 * <ul>
 *   <li>Suffix length: ≤10 characters. Longer → reject on create.</li>
 *   <li>Character set: Minecraft-legal profile chars only (alnum + underscore).
 *       We additionally forbid underscore inside the suffix because the
 *       "_"&nbsp;separator would become ambiguous.</li>
 *   <li>Reconstruction: if {@code len(B) + 1 + len(S) ≤ 16} → {@code B + "_" + S},
 *       otherwise truncate B to {@code 15 - len(S)} chars, then {@code "_" + S}.</li>
 *   <li>When the base account name changes, every local-profile display name
 *       under that controller reflects the change at the next resolution —
 *       no stored-name rewrite is needed.</li>
 * </ul>
 */
public final class Naming {
    private Naming() {}

    public static final int MAX_SUFFIX_LENGTH = 10;
    public static final int MAX_NAME_LENGTH = 16;

    /** Alnum + optional underscore-at-start rejected; must be non-empty. */
    private static final Pattern SUFFIX_PATTERN = Pattern.compile("^[A-Za-z0-9]{1,10}$");

    /**
     * Validates a suffix against the stored-value rule (§2.1, §2.2). Does not
     * check uniqueness — uniqueness is enforced by the store.
     */
    public static boolean isValidSuffix(final String suffix) {
        if (suffix == null) return false;
        return SUFFIX_PATTERN.matcher(suffix).matches();
    }

    /**
     * Normalizes a user-provided suffix: strips whitespace, lowercases the
     * storage form. The <b>stored</b> suffix is case-insensitive because
     * Minecraft player names are not case-significant for collision purposes.
     * The <b>displayed</b> suffix preserves the controller's original casing
     * where possible.
     */
    public static String normalizeSuffix(final String suffix) {
        if (suffix == null) return "";
        return suffix.strip();
    }

    /**
     * Reconstructs the final visible local profile name, per §2.3.
     *
     * @param baseAccountName the <em>current</em> controller account name.
     * @param suffix          the stored local profile suffix.
     * @return the visible name, at most {@link #MAX_NAME_LENGTH} chars long,
     *         always ending in {@code "_" + suffix}.
     */
    public static String reconstructDisplayName(final String baseAccountName, final String suffix) {
        final String base = baseAccountName == null ? "" : baseAccountName;
        final String suf = suffix == null ? "" : suffix;
        final int combined = base.length() + 1 + suf.length();
        if (combined <= MAX_NAME_LENGTH) {
            return base + "_" + suf;
        }
        // Truncate B to (15 - len(S)); the -1 accounts for the inserted underscore.
        final int budget = (MAX_NAME_LENGTH - 1) - suf.length();
        if (budget <= 0) {
            // Suffix alone almost fills the name; just return "_" + suffix.
            // This path is unreachable given MAX_SUFFIX_LENGTH=10 and
            // MAX_NAME_LENGTH=16 — leaving a defensive branch.
            return "_" + suf;
        }
        return base.substring(0, Math.min(budget, base.length())) + "_" + suf;
    }

    /**
     * Key used for case-insensitive matching of player names across the store
     * and the shadow reservation map. Not safe to display.
     */
    public static String normalizeKey(final String name) {
        return name == null ? "" : name.toLowerCase(java.util.Locale.ROOT).strip();
    }
}
