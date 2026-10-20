package vn.vumap.signs;

/**
 * Pack envelope presented through the SAF
 * ({@code ACTION_OPEN_DOCUMENT}) import or a repo-committed bundle.
 */
public final class PackManifest {
    public final int schemaVersion;
    public final String source;
    public final String license;
    public final String version;
    /** Lower-case hex SHA-256 the importer must reproduce; null skips the check. */
    public final String expectedSha256;

    public PackManifest(
            int schemaVersion, String source, String license, String version,
            String expectedSha256) {
        this.schemaVersion = schemaVersion;
        this.source = source;
        this.license = license;
        this.version = version;
        this.expectedSha256 = expectedSha256;
    }
}
