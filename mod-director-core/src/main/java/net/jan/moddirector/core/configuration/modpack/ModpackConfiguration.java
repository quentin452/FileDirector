package net.jan.moddirector.core.configuration.modpack;

import java.net.URL;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ModpackConfiguration {
    private final String packName;
    private final ModpackIconConfiguration icon;
    private final String localVersion;
    private final String mcVersion;
    private final URL remoteVersion;
    private final boolean refuseLaunch;

    @JsonCreator
    public ModpackConfiguration(
            @JsonProperty(value = "packName", required = true) String packName,
            @JsonProperty("icon") ModpackIconConfiguration icon,
            @JsonProperty("localVersion") String localVersion,
            @JsonProperty("mcVersion") String mcVersion,
            @JsonProperty("remoteVersion") URL remoteVersion,
            @JsonProperty("refuseLaunch") boolean refuseLaunch
    ) {
        // Validate that packName doesn't contain any version patterns
        // With mcVersion field available, there's no reason to include versions in packName
        if (packName != null && containsVersionPattern(packName)) {
            throw new IllegalArgumentException(
                "ERROR: The 'packName' field in modpack.json contains a version number.\n" +
                "Current packName: \"" + packName + "\"\n\n" +
                "Please remove ALL versions from packName and use the dedicated fields instead:\n" +
                "  - Use 'localVersion' for modpack version (e.g., \"V1.0.9\")\n" +
                "  - Use 'mcVersion' for Minecraft version (e.g., \"1.7.10\")\n\n" +
                "Example:\n" +
                "  \"packName\": \"Biggess Pack Cat Edition\",\n" +
                "  \"mcVersion\": \"1.7.10\",\n" +
                "  \"localVersion\": \"V1.0.9\"\n\n" +
                "The packName should remain constant across updates for proper mod tracking."
            );
        }
        
        this.packName = packName;
        this.icon = icon;
        this.localVersion = localVersion;
        this.mcVersion = mcVersion;
        this.remoteVersion = remoteVersion;
        this.refuseLaunch = refuseLaunch;
    }

    /**
     * Check if the pack name contains any version patterns.
     * Now detects ALL version patterns since we have dedicated fields for versions.
     */
    private static boolean containsVersionPattern(String name) {
        // Check for any version pattern: V1.0.9, v1.0.9, 1.0.9, 1.7.10, etc.
        // We want packName to be clean without ANY version numbers
        return name.matches(".*[_\\s-]?[vV]?\\d+\\.\\d+(\\.\\d+)?.*");
    }

    public String packName() {
        return packName;
    }

    public ModpackIconConfiguration icon() {
        return icon;
    }

    public String localVersion() {
        return localVersion;
    }

    public String mcVersion() {
        return mcVersion;
    }

    public URL remoteVersion() {
        return remoteVersion;
    }

    public boolean refuseLaunch() {
        return refuseLaunch;
    }

    public static ModpackConfiguration createDefault() {
        return new ModpackConfiguration(
                "Modpack",
                null,
                null,
                null,
                null,
                false
        );
    }
}
