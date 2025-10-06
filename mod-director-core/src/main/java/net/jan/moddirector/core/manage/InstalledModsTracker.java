package net.jan.moddirector.core.manage;

import com.fasterxml.jackson.annotation.JsonProperty;
import net.jan.moddirector.core.ModDirector;
import net.jan.moddirector.core.configuration.ConfigurationController;
import net.jan.moddirector.core.logging.ModDirectorSeverityLevel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Tracks mods that have been installed by ModDirector to enable cleanup
 * of old versions when configuration changes.
 */
public class InstalledModsTracker {
    private static final String TRACKING_FILE = "installed-mods.json";
    private static final String LOG_DOMAIN = "ModDirector/InstalledModsTracker";
    private static final String FILE_DIRECTOR_DIR = "file-director-snap";

    private final ModDirector director;
    private Path trackingFilePath;
    private TrackingData data;
    private boolean initialized = false;

    public InstalledModsTracker(ModDirector director) {
        this.director = director;
        this.data = new TrackingData();
    }

    /**
     * Initialize the tracker (must be called after platform bootstrap)
     */
    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        
        this.trackingFilePath = getTrackingFileLocation();
        load();
        initialized = true;
    }

    /**
     * Get the tracking file location in the user's home directory.
     * Structure: %USERPROFILE%/file-director-snap/{modpackName}/{mcVersion}/{side}/installed-mods.json
     * or ~/.file-director-snap/{modpackName}/{mcVersion}/{side}/installed-mods.json on Unix systems
     * 
     * The 'side' component (client/server) prevents conflicts when running both client and server
     * with different mod configurations in the same installation directory.
     */
    private Path getTrackingFileLocation() {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isEmpty()) {
            director.getLogger().log(ModDirectorSeverityLevel.WARN, LOG_DOMAIN,
                    "CORE", "user.home system property not set, falling back to installation root");
            return director.getPlatform().installationRoot().resolve(TRACKING_FILE);
        }

        // Get modpack name, Minecraft version, and side (client/server) for folder structure
        String modpackName = "default";
        String mcVersion = "unknown";
        String side = "unknown";
        
        try {
            if (director.getConfigurationController() != null 
                    && director.getConfigurationController().getModpackConfiguration() != null) {
                String packName = director.getConfigurationController().getModpackConfiguration().packName();
                if (packName != null && !packName.isEmpty()) {
                    // Sanitize the modpack name to be filesystem-safe
                    // Note: packName is validated to not contain versions in ModpackConfiguration constructor
                    modpackName = packName.replaceAll("[^a-zA-Z0-9._-]", "_");
                    
                    director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                            "CORE", "Using modpack name: %s", modpackName);
                }
                
                // Get Minecraft version to prevent conflicts between same modpack name on different MC versions
                String mcVer = director.getConfigurationController().getModpackConfiguration().mcVersion();
                if (mcVer != null && !mcVer.isEmpty()) {
                    // Sanitize the MC version to be filesystem-safe
                    mcVersion = mcVer.replaceAll("[^a-zA-Z0-9._-]", "_");
                    
                    director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                            "CORE", "Using Minecraft version: %s", mcVersion);
                }
            }
            
            // Get platform side (client/server) to separate tracking between client and server
            // This prevents conflicts when running both in the same installation directory
            net.jan.moddirector.core.platform.PlatformSide platformSide = director.getPlatform().side();
            if (platformSide != null) {
                side = platformSide.toString().toLowerCase();
                director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                        "CORE", "Using platform side: %s", side);
            } else {
                director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                        "CORE", "Platform side not determined, using 'unknown'");
            }
            
        } catch (Exception e) {
            director.getLogger().logThrowable(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                    "CORE", e, "Failed to get modpack info, MC version, or platform side; using defaults");
        }

        Path fileDirectorPath = java.nio.file.Paths.get(userHome, FILE_DIRECTOR_DIR, modpackName, mcVersion, side);
        Path trackingFile = fileDirectorPath.resolve(TRACKING_FILE);
        
        director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                "CORE", "Using tracking file location: %s", trackingFile.toString());
        
        return trackingFile;
    }

    /**
     * Load the tracking data from disk
     */
    private void load() {
        if (!Files.exists(trackingFilePath)) {
            director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                    "CORE", "No tracking file found, starting fresh");
            return;
        }

        try (InputStream stream = Files.newInputStream(trackingFilePath)) {
            data = ConfigurationController.OBJECT_MAPPER.readValue(stream, TrackingData.class);
            director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                    "CORE", "Loaded %d tracked mod files", data.installedFiles.size());
        } catch (IOException e) {
            director.getLogger().logThrowable(ModDirectorSeverityLevel.WARN, LOG_DOMAIN,
                    "CORE", e, "Failed to load tracking file, starting fresh");
            data = new TrackingData();
        }
    }

    /**
     * Save the tracking data to disk
     */
    public void save() {
        ensureInitialized();
        try {
            Files.createDirectories(trackingFilePath.getParent());
            try (OutputStream stream = Files.newOutputStream(trackingFilePath)) {
                ConfigurationController.OBJECT_MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValue(stream, data);
            }
            director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                    "CORE", "Saved %d tracked mod files", data.installedFiles.size());
        } catch (IOException e) {
            director.getLogger().logThrowable(ModDirectorSeverityLevel.WARN, LOG_DOMAIN,
                    "CORE", e, "Failed to save tracking file");
        }
    }

    /**
     * Track a newly installed mod file
     */
    public void trackInstalledFile(Path modFile) {
        ensureInitialized();
        String fileName = modFile.getFileName().toString();
        if (data.installedFiles.add(fileName)) {
            director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                    "CORE", "Now tracking: %s", fileName);
        }
    }

    /**
     * Remove a file from tracking (when it's deleted)
     */
    public void untrackFile(Path modFile) {
        ensureInitialized();
        String fileName = modFile.getFileName().toString();
        if (data.installedFiles.remove(fileName)) {
            director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                    "CORE", "No longer tracking: %s", fileName);
        }
    }

    /**
     * Get all tracked mod filenames
     */
    public Set<String> getTrackedFiles() {
        ensureInitialized();
        return new HashSet<>(data.installedFiles);
    }

    /**
     * Check if a file is being tracked
     */
    public boolean isTracked(String fileName) {
        ensureInitialized();
        return data.installedFiles.contains(fileName);
    }

    /**
     * Clear all tracking data
     */
    public void clear() {
        ensureInitialized();
        data.installedFiles.clear();
    }

    /**
     * Check if the tracking file is empty or doesn't exist
     */
    public boolean isEmpty() {
        ensureInitialized();
        return data.installedFiles.isEmpty();
    }

    /**
     * Get the path to the tracking file
     */
    public Path getTrackingFilePath() {
        ensureInitialized();
        return trackingFilePath;
    }

    /**
     * Internal data structure for JSON serialization
     */
    private static class TrackingData {
        @JsonProperty("installedFiles")
        public Set<String> installedFiles = new HashSet<>();
    }
}
