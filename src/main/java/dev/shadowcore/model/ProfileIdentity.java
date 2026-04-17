package dev.shadowcore.model;

import java.util.Objects;
import java.util.UUID;

/**
 * A <em>baseline</em> identity: the identity the controller would be mounted
 * on if no shadow session were active. Either the main account identity or a
 * local profile identity.
 *
 * <p>A {@code ProfileIdentity} carries two UUIDs because during normal
 * local-profile mode the controller account's UUID remains the owner of
 * permission inheritance while the profile's own UUID is the world actor.</p>
 */
public final class ProfileIdentity {
    /** The real controller account UUID — permission-authority owner. */
    private final UUID controllerUuid;
    /** The active profile UUID — for main, this equals controllerUuid. */
    private final UUID profileUuid;
    /** The stored suffix, or null for main. */
    private final String suffix;
    /** Whether this is the controller's main account identity. */
    private final boolean main;

    private ProfileIdentity(final UUID controllerUuid, final UUID profileUuid,
                            final String suffix, final boolean main) {
        this.controllerUuid = Objects.requireNonNull(controllerUuid, "controllerUuid");
        this.profileUuid = Objects.requireNonNull(profileUuid, "profileUuid");
        this.suffix = suffix;
        this.main = main;
    }

    public static ProfileIdentity main(final UUID controllerUuid) {
        return new ProfileIdentity(controllerUuid, controllerUuid, null, true);
    }

    public static ProfileIdentity local(final UUID controllerUuid, final UUID profileUuid, final String suffix) {
        Objects.requireNonNull(profileUuid, "profileUuid");
        Objects.requireNonNull(suffix, "suffix");
        if (profileUuid.equals(controllerUuid)) {
            throw new IllegalArgumentException("local profile UUID must differ from controller UUID");
        }
        return new ProfileIdentity(controllerUuid, profileUuid, suffix, false);
    }

    public UUID controllerUuid() { return controllerUuid; }
    public UUID profileUuid() { return profileUuid; }
    public String suffix() { return suffix; }
    public boolean isMain() { return main; }
    public boolean isLocalProfile() { return !main; }

    /** The UUID that vanilla should key .dat files / ownership by. */
    public UUID worldActorUuid() { return profileUuid; }

    @Override public boolean equals(final Object o) {
        if (!(o instanceof ProfileIdentity p)) return false;
        return controllerUuid.equals(p.controllerUuid)
            && profileUuid.equals(p.profileUuid)
            && Objects.equals(suffix, p.suffix)
            && main == p.main;
    }
    @Override public int hashCode() { return Objects.hash(controllerUuid, profileUuid, suffix, main); }
    @Override public String toString() {
        return main ? ("ProfileIdentity[main " + controllerUuid + "]")
                    : ("ProfileIdentity[local " + profileUuid + " of " + controllerUuid + " suffix=" + suffix + "]");
    }
}
