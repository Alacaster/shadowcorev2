package dev.shadowcore.model;

/**
 * The persistence policy applied when a mounted identity is detached.
 *
 * <p>{@link #COMMIT} runs vanilla save against the mounted identity — the
 * mounted changes become canonical in the mounted identity's .dat file. This
 * is the normal behavior for local-profile switching and for {@code /shadow
 * logout}.</p>
 *
 * <p>{@link #DISCARD} throws away the mounted changes without saving. The
 * mounted identity's .dat file retains whatever was there <em>before</em> the
 * mount. This is what {@code /shadow discard} and forced auto-discard do.</p>
 */
public enum MountDisposition {
    COMMIT,
    DISCARD
}
