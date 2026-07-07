package net.jan.moddirector.core.manage;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import net.jan.moddirector.core.ModDirector;
import net.jan.moddirector.core.configuration.ConfigurationController;
import net.jan.moddirector.core.configuration.RemoteModInformation;
import net.jan.moddirector.core.logging.ModDirectorSeverityLevel;
import net.jan.moddirector.core.platform.PlatformSide;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * On-disk cache of {@link RemoteModInformation}, keyed by an immutable per-file identity
 * ({@code remoteType() + "|" + offlineName()}). It is populated during the network-bound
 * "querying mod information" pre-install phase so that subsequent (warm) boots can skip the
 * per-mod HTTP query entirely.
 * <p>
 * Correctness: the key embeds the CurseForge/Modrinth file id (or the full URL for URL mods),
 * all of which are immutable, so any bundle change produces a new key and thus a natural cache
 * miss. Stale entries for removed mods simply linger harmlessly.
 * <p>
 * This class is fail-open: any resolve/load/save/parse failure is logged at debug and swallowed,
 * so a cache problem can never throw into the boot path. The backing map is concurrent because
 * {@link #put} is invoked from the parallel pre-install worker threads.
 * <p>
 * The cache file lives next to {@link InstalledModsTracker}'s tracking file (same
 * {@code file-director-snap/{modpack}/{mcVersion}/{side}} directory), named {@code .modinfo-cache.json}.
 * Path resolution and the initial load are deferred until first use, because the modpack
 * configuration is not loaded yet when the owning {@link InstallController} is constructed.
 */
public class ModInfoDiskCache {
    private static final String LOG_DOMAIN = "ModDirector/ModInfoDiskCache";
    private static final String CACHE_FILE = ".modinfo-cache.json";
    private static final String FILE_DIRECTOR_DIR = "file-director-snap";

    private final ModDirector director;
    private final ConcurrentHashMap<String, RemoteModInformation> entries = new ConcurrentHashMap<>();

    private Path cacheFilePath;
    private volatile boolean loaded = false;
    private volatile boolean dirty = false;

    public ModInfoDiskCache(ModDirector director) {
        this.director = director;
    }

    public RemoteModInformation get(String key) {
        if(key == null) {
            return null;
        }
        ensureLoaded();
        return entries.get(key);
    }

    public void put(String key, RemoteModInformation info) {
        if(key == null || info == null) {
            return;
        }
        ensureLoaded();
        entries.put(key, info);
        dirty = true;
    }

    /**
     * Writes the cache to disk, but only if it changed since it was loaded. Never throws.
     */
    public void save() {
        ensureLoaded();
        if(!dirty || cacheFilePath == null) {
            return;
        }
        try {
            Map<String, Entry> out = new HashMap<>();
            for(Map.Entry<String, RemoteModInformation> e : entries.entrySet()) {
                RemoteModInformation info = e.getValue();
                out.put(e.getKey(), new Entry(info.getDisplayName(), info.getTargetFilename()));
            }
            Files.createDirectories(cacheFilePath.getParent());
            try(OutputStream stream = Files.newOutputStream(cacheFilePath)) {
                ConfigurationController.OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(stream, out);
            }
            dirty = false;
            director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                    "CORE", "Saved %d cached mod info entries", out.size());
        } catch(Exception e) {
            director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                    "CORE", "Failed to save mod info cache: %s", String.valueOf(e.getMessage()));
        }
    }

    private synchronized void ensureLoaded() {
        if(loaded) {
            return;
        }
        cacheFilePath = resolveCacheFileLocation();
        load();
        loaded = true;
    }

    private void load() {
        if(cacheFilePath == null) {
            return;
        }
        try {
            if(!Files.exists(cacheFilePath)) {
                director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                        "CORE", "No mod info cache found, starting empty");
                return;
            }
            try(InputStream stream = Files.newInputStream(cacheFilePath)) {
                Map<String, Entry> loadedEntries = ConfigurationController.OBJECT_MAPPER.readValue(
                        stream, new TypeReference<Map<String, Entry>>() { });
                if(loadedEntries != null) {
                    for(Map.Entry<String, Entry> e : loadedEntries.entrySet()) {
                        Entry v = e.getValue();
                        if(v != null && v.displayName != null && v.targetFilename != null) {
                            entries.put(e.getKey(), new RemoteModInformation(v.displayName, v.targetFilename));
                        }
                    }
                }
            }
            director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                    "CORE", "Loaded %d cached mod info entries", entries.size());
        } catch(Exception e) {
            director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                    "CORE", "Failed to load mod info cache, starting empty: %s", String.valueOf(e.getMessage()));
            entries.clear();
        }
    }

    /**
     * Mirrors {@link InstalledModsTracker}'s file location so the cache sits next to the
     * tracking file: {@code ~/file-director-snap/{modpack}/{mcVersion}/{side}/.modinfo-cache.json}.
     */
    private Path resolveCacheFileLocation() {
        try {
            String userHome = System.getProperty("user.home");
            if(userHome == null || userHome.isEmpty()) {
                return director.getPlatform().installationRoot().resolve(CACHE_FILE);
            }

            String modpackName = "default";
            String mcVersion = "unknown";
            String side = "unknown";

            if(director.getConfigurationController() != null
                    && director.getConfigurationController().getModpackConfiguration() != null) {
                String packName = director.getConfigurationController().getModpackConfiguration().packName();
                if(packName != null && !packName.isEmpty()) {
                    modpackName = packName.replaceAll("[^a-zA-Z0-9._-]", "_");
                }
                String mcVer = director.getConfigurationController().getModpackConfiguration().mcVersion();
                if(mcVer != null && !mcVer.isEmpty()) {
                    mcVersion = mcVer.replaceAll("[^a-zA-Z0-9._-]", "_");
                }
            }

            PlatformSide platformSide = director.getPlatform().side();
            if(platformSide != null) {
                side = platformSide.toString().toLowerCase();
            }

            return Paths.get(userHome, FILE_DIRECTOR_DIR, modpackName, mcVersion, side).resolve(CACHE_FILE);
        } catch(Exception e) {
            director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                    "CORE", "Failed to resolve mod info cache location, cache disabled: %s",
                    String.valueOf(e.getMessage()));
            return null;
        }
    }

    /**
     * Jackson-friendly DTO mirroring {@link RemoteModInformation}'s two fields. Kept private so
     * {@code RemoteModInformation}'s existing (constructor-only, no-getter-setter) API is untouched.
     */
    private static class Entry {
        @JsonProperty("displayName")
        public String displayName;

        @JsonProperty("targetFilename")
        public String targetFilename;

        public Entry() {
        }

        public Entry(String displayName, String targetFilename) {
            this.displayName = displayName;
            this.targetFilename = targetFilename;
        }
    }
}
