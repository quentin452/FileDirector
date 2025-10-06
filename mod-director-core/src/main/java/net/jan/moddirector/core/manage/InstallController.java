package net.jan.moddirector.core.manage;

import net.jan.moddirector.core.ModDirector;
import net.jan.moddirector.core.configuration.ModDirectorRemoteMod;
import net.jan.moddirector.core.configuration.RemoteModInformation;
import net.jan.moddirector.core.configuration.modpack.ModpackConfiguration;
import net.jan.moddirector.core.exception.ModDirectorException;
import net.jan.moddirector.core.logging.ModDirectorSeverityLevel;
import net.jan.moddirector.core.manage.install.InstallableMod;
import net.jan.moddirector.core.manage.install.InstalledMod;
import net.jan.moddirector.core.util.HashResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;

public class InstallController {
    private static final String LOG_DOMAIN = "ModDirector/InstallController";
    private final ModDirector director;
    private final InstalledModsTracker tracker;

    public InstallController(ModDirector director, InstalledModsTracker tracker) {
        this.director = director;
        this.tracker = tracker;
    }

    private ModDirectorSeverityLevel downloadSeverityLevelFor(ModDirectorRemoteMod mod) {
        return mod.getInstallationPolicy().shouldContinueOnFailedDownload() ?
                ModDirectorSeverityLevel.WARN : ModDirectorSeverityLevel.ERROR;
    }

    // Cache for mod information to avoid duplicate queries
    private final java.util.Map<ModDirectorRemoteMod, RemoteModInformation> modInfoCache = new java.util.HashMap<>();

    public List<Callable<Void>> createPreInstallTasks(
            List<ModDirectorRemoteMod> allMods,
            List<ModDirectorRemoteMod> excludedMods,
            List<InstallableMod> freshMods,
            List<InstallableMod> reinstallMods,
            BiFunction<String, String, ProgressCallback> callbackFactory
    ) {
        List<Callable<Void>> preInstallTasks = new ArrayList<>();

        for(ModDirectorRemoteMod mod : allMods) {
            preInstallTasks.add(() -> {
                ProgressCallback callback = callbackFactory.apply(mod.offlineName(), "Checking installation status");

                callback.indeterminate(true);
                callback.message("Checking installation requirements");

                if(mod.getMetadata() != null && !mod.getMetadata().shouldTryInstall(director)) {
                    director.getLogger().log(
                            ModDirectorSeverityLevel.DEBUG,
                            "ModDirector/InstallSelector",
                            "CORE",
                            "Skipping mod %s because shouldTryInstall() returned false",
                            mod.offlineName()
                    );

                    excludedMods.add(mod);

                    callback.done();
                    return null;
                }

                callback.message("Querying mod information");

                RemoteModInformation information;

                try {
                    information = mod.queryInformation();
                    // Cache the information for later use
                    synchronized(modInfoCache) {
                        modInfoCache.put(mod, information);
                    }
                } catch(ModDirectorException e) {
                    director.getLogger().logThrowable(ModDirectorSeverityLevel.ERROR, LOG_DOMAIN,
                            "CORE", e, "Failed to query information for %s from %s",
                            mod.offlineName(), mod.remoteType());
                    director.addError(new ModDirectorError(downloadSeverityLevelFor(mod),
                            "Failed to query information for mod " + mod.offlineName() + " from " + mod.remoteType(),
                            e));
                    callback.done();
                    return null;
                }

                callback.title(information.getDisplayName());
                Path targetFile = computeInstallationTargetPath(mod, information);

                if(targetFile == null) {
                    callback.done();
                    return null;
                }

                Path disabledFile = computeDisabledPath(targetFile);

                if(Files.isRegularFile(disabledFile) || !isVersionCompliant(mod)) {
                    excludedMods.add(mod);
                    callback.done();
                    return null;
                }

                InstallableMod installableMod = new InstallableMod(mod, information, targetFile);

                Path bansoukouPatchedFile = computeBansoukouPatchedPath(targetFile);
                Path bansoukouDisabledFile = computeBansoukouDisabledPath(targetFile);

                if(mod.getMetadata() != null && (Files.isRegularFile(targetFile) || (Files.isRegularFile(bansoukouPatchedFile) && Files.isRegularFile(bansoukouDisabledFile)))) {
                    HashResult hashResult = mod.getMetadata().checkHashes(Files.isRegularFile(targetFile) ? targetFile : bansoukouDisabledFile, director);

                    switch(hashResult) {
                        case UNKNOWN:
                            director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                                    "CORE", "Skipping download of %s as hashes can't be determined but file exists",
                                    targetFile.toString());
                            callback.done();

                            excludedMods.add(mod);
                            return null;

                        case MATCHED:
                            director.getLogger().log(ModDirectorSeverityLevel.INFO, LOG_DOMAIN,
                                    "CORE", "Skipping download of %s as the hashes match", targetFile.toString());
                            callback.done();

                            excludedMods.add(mod);
                            return null;

                        case UNMATCHED:
                            director.getLogger().log(ModDirectorSeverityLevel.WARN, LOG_DOMAIN,
                                    "CORE", "File %s exists, but hashes do not match, downloading again!",
                                    targetFile.toString());
                    }
                    Files.deleteIfExists(bansoukouPatchedFile);
                    Files.deleteIfExists(bansoukouDisabledFile);
                    reinstallMods.add(installableMod);

                } else if(mod.getInstallationPolicy().shouldDownloadAlways() && Files.isRegularFile(targetFile)) {
                    director.getLogger().log(ModDirectorSeverityLevel.INFO, LOG_DOMAIN,
                        "CORE", "Force downloading file %s as download always option is set.",
                        targetFile.toString());
                    reinstallMods.add(installableMod);

                } else if(Files.isRegularFile(targetFile)) {
                    director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                            "CORE", "File %s exists and no metadata given, skipping download.",
                            targetFile.toString());
                    excludedMods.add(mod);

                } else {
                    freshMods.add(installableMod);
                }

                if(!excludedMods.contains(mod) && mod.getInstallationPolicy().getSupersededFileName() != null) {
                    Path supersededFile = targetFile.resolveSibling(mod.getInstallationPolicy().getSupersededFileName());
                    if(Files.isRegularFile(supersededFile)) {
                        director.getLogger().log(ModDirectorSeverityLevel.INFO, "ModDirector/ConfigurationController",
                            "CORE", "Superseding %s", targetFile);
                        Files.move(supersededFile, supersededFile.resolveSibling(supersededFile.getFileName() + ".disabled-by-mod-director"));
                    }
                }

                callback.done();
                return null;
            });
        }

        return preInstallTasks;
    }

    private Path computeInstallationTargetPath(ModDirectorRemoteMod mod, RemoteModInformation information) {
        Path installationRoot = director.getPlatform().installationRoot().toAbsolutePath().normalize();

        Path targetFile = (mod.getFolder() == null ?
                director.getPlatform().modFile(information.getTargetFilename())
                : mod.getFolder().equalsIgnoreCase(".") ?
                director.getPlatform().rootFile(information.getTargetFilename())
                : director.getPlatform().customFile(information.getTargetFilename(), mod.getFolder()))
                .toAbsolutePath().normalize();

        if(!targetFile.startsWith(installationRoot)) {
            director.getLogger().log(ModDirectorSeverityLevel.ERROR, LOG_DOMAIN,
                    "CORE", "Tried to install a file to %s, which is outside the installation root of %s!",
                    targetFile.toString(), director.getPlatform().installationRoot());
            director.addError(new ModDirectorError(ModDirectorSeverityLevel.ERROR,
                    "Tried to install a file to " + targetFile + ", which is outside of " +
                            "the installation root " + installationRoot));
            return null;
        }

        return targetFile;
    }

    private Path computeDisabledPath(Path modFile) {
        return modFile.resolveSibling(modFile.getFileName() + ".disabled-by-mod-director");
    }

    private Path computeBansoukouPatchedPath(Path modFile) {
        return modFile.resolveSibling(modFile.getFileName().toString().replace(".jar","-patched.jar"));
    }

    private Path computeBansoukouDisabledPath(Path modFile) {
        return modFile.resolveSibling(modFile.getFileName().toString().replace(".jar",".disabled"));
    }

    private boolean isVersionCompliant(ModDirectorRemoteMod mod) {
        String versionMod = mod.getInstallationPolicy().getModpackVersion();
        String versionModpackRemote = director.getModpackRemoteVersion();

        ModpackConfiguration modpackConfiguration = director.getConfigurationController().getModpackConfiguration();
        String versionModpackLocal = null;
        if(modpackConfiguration != null) {
            versionModpackLocal = modpackConfiguration.localVersion();
        }

        if(versionMod != null) {
            if(versionModpackRemote != null) {
                return Objects.equals(versionMod, versionModpackRemote);
            } else if(versionModpackLocal != null) {
                return Objects.equals(versionMod, versionModpackLocal);
            }
        }
        return true;
    }

    public void markDisabledMods(List<InstallableMod> mods) {
        for(InstallableMod mod : mods) {
            try {
                Path disabledFile = computeDisabledPath(mod.getTargetFile());

                Files.createDirectories(disabledFile.getParent());
                Files.createFile(disabledFile);
            } catch (IOException e) {
                director.getLogger().logThrowable(
                        ModDirectorSeverityLevel.WARN,
                        LOG_DOMAIN,
                        "CORE",
                        e,
                        "Failed to create disabled file, the user might be asked again if he wants to install the mod"
                );

                director.addError(new ModDirectorError(
                        ModDirectorSeverityLevel.WARN,
                        "Failed to create disabled file",
                        e
                ));
            }
        }
    }

    public List<Callable<Void>> createInstallTasks(
            List<InstallableMod> mods,
            BiFunction<String, String, ProgressCallback> callbackFactory
    ) {
        List<Callable<Void>> installTasks = new ArrayList<>();

        for(InstallableMod mod : mods) {
            installTasks.add(() -> {
                handle(mod, callbackFactory.apply(mod.getRemoteInformation().getTargetFilename(), "Installing"));
                return null;
            });
        }

        return installTasks;
    }

    private void handle(InstallableMod mod, ProgressCallback callback) {
        ModDirectorRemoteMod remoteMod = mod.getRemoteMod();

        director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN, "CORE",
                "Now handling %s from backend %s", remoteMod.offlineName(), remoteMod.remoteType());

        Path targetFile = mod.getTargetFile();

        try {
            Files.createDirectories(targetFile.getParent());
        } catch(IOException e) {
            director.getLogger().logThrowable(ModDirectorSeverityLevel.ERROR, LOG_DOMAIN,
                    "CORE", e, "Failed to create directory %s", targetFile.getParent().toString());
            director.addError(new ModDirectorError(ModDirectorSeverityLevel.ERROR,
                    "Failed to create directory" + targetFile.getParent().toString(), e));
            callback.done();
            return;
        }

        try {
            mod.performInstall(director, callback);
        } catch(ModDirectorException e) {
            director.getLogger().logThrowable(ModDirectorSeverityLevel.ERROR, LOG_DOMAIN,
                    "CORE", e, "Failed to install mod %s", remoteMod.offlineName());
            director.addError(new ModDirectorError(downloadSeverityLevelFor(remoteMod),
                    "Failed to install mod "  + remoteMod.offlineName(), e));
            callback.done();
            return;
        }

        if(remoteMod.getMetadata() != null && remoteMod.getMetadata().checkHashes(targetFile, director) == HashResult.UNMATCHED) {
            director.getLogger().log(ModDirectorSeverityLevel.ERROR, LOG_DOMAIN,
                    "CORE", "Mod did not match hash after download, aborting!");
            director.addError(new ModDirectorError(ModDirectorSeverityLevel.ERROR,
                    "Mod did not match hash after download"));
        } else {
            if(remoteMod.getInstallationPolicy().shouldExtract()) {
                director.getLogger().log(ModDirectorSeverityLevel.INFO, LOG_DOMAIN,
                    "CORE", "Extracted mod file %s", targetFile.toString());
            } else {
                director.getLogger().log(ModDirectorSeverityLevel.INFO, LOG_DOMAIN,
                    "CORE", "Installed mod file %s", targetFile.toString());
            }
            director.installSuccess(new InstalledMod(targetFile, remoteMod.getOptions(), remoteMod.forceInject()));
            
            // Track this installed file
            tracker.trackInstalledFile(targetFile);
        }

        callback.done();
    }

    /**
     * Identifies mod files that are no longer in the configuration and should be removed.
     * Returns a list of old mod files for user confirmation before deletion.
     * 
     * @param allMods Complete list of all mods from the configuration
     * @param freshMods List of mods to be freshly installed
     * @param reinstallMods List of mods to be reinstalled
     * @return List of paths to old mod files that can be removed
     */
    public List<Path> identifyOldMods(List<ModDirectorRemoteMod> allMods, List<InstallableMod> freshMods, List<InstallableMod> reinstallMods) {
        List<Path> oldModsToRemove = new ArrayList<>();
        
        try {
            // Reconstruct tracking data if the tracking file is empty or missing
            // This handles retroactive compatibility with older modpack versions
            reconstructTrackingFromExistingFiles(allMods);

            // Build a set of all expected mod filenames from ALL mods in configuration
            Set<String> expectedModFiles = new HashSet<>();
            
            // Process all mods from configuration to get their expected filenames
            for (ModDirectorRemoteMod mod : allMods) {
                try {
                    // Use cached information if available to avoid duplicate queryInformation() calls
                    RemoteModInformation information = modInfoCache.get(mod);
                    if (information == null) {
                        // Fallback to querying if not in cache (shouldn't happen normally)
                        director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                                "CORE", "Mod information not in cache, querying: %s", mod.offlineName());
                        information = mod.queryInformation();
                    }
                    Path targetFile = computeInstallationTargetPath(mod, information);
                    
                    if (targetFile != null) {
                        expectedModFiles.add(targetFile.getFileName().toString());
                        
                        // Also track disabled and bansoukou variants
                        Path disabledFile = computeDisabledPath(targetFile);
                        expectedModFiles.add(disabledFile.getFileName().toString());
                        
                        Path bansoukouPatchedFile = computeBansoukouPatchedPath(targetFile);
                        expectedModFiles.add(bansoukouPatchedFile.getFileName().toString());
                        
                        Path bansoukouDisabledFile = computeBansoukouDisabledPath(targetFile);
                        expectedModFiles.add(bansoukouDisabledFile.getFileName().toString());
                    }
                } catch (Exception e) {
                    director.getLogger().logThrowable(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                            "CORE", e, "Failed to get information for mod %s during cleanup", mod.offlineName());
                }
            }
            
            // Combine freshMods and reinstallMods for migration
            List<InstallableMod> allInstallableMods = new ArrayList<>();
            allInstallableMods.addAll(freshMods);
            allInstallableMods.addAll(reinstallMods);
            
            // Migration: Track existing mod files that match the configuration
            // This handles mods that were installed before the tracking system was implemented
            migrateExistingModsToTracking(allInstallableMods);
            
            // Get all tracked files
            Set<String> trackedFiles = tracker.getTrackedFiles();
            
            // Get the mods directory - use a dummy filename to ensure we get the mods directory itself
            Path modsDir = director.getPlatform().modFile("dummy.jar").getParent();
            if (modsDir == null || !Files.isDirectory(modsDir)) {
                director.getLogger().log(ModDirectorSeverityLevel.WARN, LOG_DOMAIN,
                        "CORE", "Mods directory not found or not a directory: %s", 
                        modsDir != null ? modsDir.toString() : "null");
                return oldModsToRemove;
            }

            // Get the installation root for searching in other directories
            Path installationRoot = director.getPlatform().installationRoot();
            
            director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                    "CORE", "Mods directory: %s, Installation root: %s", 
                    modsDir.toString(), installationRoot.toString());

            // Find tracked files that are no longer expected
            for (String trackedFileName : trackedFiles) {
                if (!expectedModFiles.contains(trackedFileName)) {
                    director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                            "CORE", "Found old tracked mod that is no longer in config: %s", trackedFileName);
                    
                    // This file was installed by ModDirector but is no longer in the config
                    // Try to find it in multiple locations
                    Path fileToRemove = null;
                    
                    // First try in mods directory
                    Path modsPath = modsDir.resolve(trackedFileName);
                    director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                            "CORE", "Checking if file exists at: %s (exists: %s)", modsPath.toString(), Files.exists(modsPath));
                    
                    if (Files.exists(modsPath)) {
                        fileToRemove = modsPath;
                    } else {
                        // Try in installation root
                        Path rootPath = installationRoot.resolve(trackedFileName);
                        director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                                "CORE", "Checking if file exists at: %s (exists: %s)", rootPath.toString(), Files.exists(rootPath));
                        
                        if (Files.exists(rootPath)) {
                            fileToRemove = rootPath;
                        }
                    }
                    
                    if (fileToRemove != null) {
                        oldModsToRemove.add(fileToRemove);
                    } else {
                        // File doesn't exist anymore but is tracked - just untrack it
                        director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                                "CORE", "Tracked file not found, will remove from tracking: %s", trackedFileName);
                        Path dummyPath = modsDir.resolve(trackedFileName);
                        tracker.untrackFile(dummyPath);
                    }
                }
            }
            
            if (!oldModsToRemove.isEmpty()) {
                director.getLogger().log(ModDirectorSeverityLevel.INFO, LOG_DOMAIN,
                        "CORE", "Found %d old mod file(s) for potential removal", oldModsToRemove.size());
            }
            
        } catch (Exception e) {
            director.getLogger().logThrowable(ModDirectorSeverityLevel.WARN, LOG_DOMAIN,
                    "CORE", e, "Failed to identify old mods");
        }
        
        return oldModsToRemove;
    }

    /**
     * Removes the specified old mod files that the user has confirmed for deletion.
     * 
     * @param oldModsToRemove List of paths to old mod files to remove
     */
    public void removeOldMods(List<Path> oldModsToRemove) {
        if (oldModsToRemove == null || oldModsToRemove.isEmpty()) {
            director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                    "CORE", "No old mods to remove");
            clearModInfoCache();
            return;
        }

        int removedCount = 0;
        for (Path fileToRemove : oldModsToRemove) {
            try {
                director.getLogger().log(ModDirectorSeverityLevel.INFO, LOG_DOMAIN,
                        "CORE", "Removing old mod file: %s", fileToRemove.toString());
                Files.delete(fileToRemove);
                director.getLogger().log(ModDirectorSeverityLevel.INFO, LOG_DOMAIN,
                        "CORE", "Successfully removed old mod file: %s", fileToRemove.getFileName());
                removedCount++;
                
                // Remove from tracking
                tracker.untrackFile(fileToRemove);
                
            } catch (IOException e) {
                director.getLogger().logThrowable(ModDirectorSeverityLevel.WARN, LOG_DOMAIN,
                        "CORE", e, "Failed to remove old mod file: %s", fileToRemove.getFileName());
            }
        }
        
        if (removedCount > 0) {
            director.getLogger().log(ModDirectorSeverityLevel.INFO, LOG_DOMAIN,
                    "CORE", "Removed %d old mod file(s)", removedCount);
            // Save tracker after modifications
            tracker.save();
        }
        
        // Clear the cache after cleanup is complete to free memory
        clearModInfoCache();
    }

    /**
     * Clears the mod information cache.
     * This should be called after all mod operations are complete to free up memory.
     */
    public void clearModInfoCache() {
        int cacheSize = modInfoCache.size();
        modInfoCache.clear();
        director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                "CORE", "Cleared mod information cache (%d entries)", cacheSize);
    }

    /**
     * Reconstructs the tracking data from existing mod files on disk.
     * This is useful for retroactive compatibility when migrating from older
     * modpack versions that didn't use the tracking system.
     * 
     * @param allMods Complete list of all mods from the configuration
     */
    public void reconstructTrackingFromExistingFiles(List<ModDirectorRemoteMod> allMods) {
        if (!tracker.isEmpty()) {
            director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                    "CORE", "Tracking file already exists and is not empty, skipping reconstruction");
            return;
        }

        director.getLogger().log(ModDirectorSeverityLevel.INFO, LOG_DOMAIN,
                "CORE", "Tracking file is empty or missing, reconstructing from existing mod files...");

        int reconstructedCount = 0;

        for (ModDirectorRemoteMod mod : allMods) {
            try {
                // Use cached information if available
                RemoteModInformation information = modInfoCache.get(mod);
                if (information == null) {
                    // Query if not in cache
                    information = mod.queryInformation();
                }

                Path targetFile = computeInstallationTargetPath(mod, information);
                if (targetFile == null) {
                    continue;
                }

                // Check if the main file exists and track it
                if (Files.exists(targetFile)) {
                    tracker.trackInstalledFile(targetFile);
                    director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                            "CORE", "Reconstructed tracking for: %s", targetFile.getFileName());
                    reconstructedCount++;
                }

                // Also check for disabled variant
                Path disabledFile = computeDisabledPath(targetFile);
                if (Files.exists(disabledFile)) {
                    tracker.trackInstalledFile(disabledFile);
                    director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                            "CORE", "Reconstructed tracking for disabled file: %s", disabledFile.getFileName());
                    reconstructedCount++;
                }

                // Check for bansoukou patched variant
                Path bansoukouPatchedFile = computeBansoukouPatchedPath(targetFile);
                if (Files.exists(bansoukouPatchedFile)) {
                    tracker.trackInstalledFile(bansoukouPatchedFile);
                    director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                            "CORE", "Reconstructed tracking for bansoukou patched file: %s", bansoukouPatchedFile.getFileName());
                    reconstructedCount++;
                }

                // Check for bansoukou disabled variant
                Path bansoukouDisabledFile = computeBansoukouDisabledPath(targetFile);
                if (Files.exists(bansoukouDisabledFile)) {
                    tracker.trackInstalledFile(bansoukouDisabledFile);
                    director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                            "CORE", "Reconstructed tracking for bansoukou disabled file: %s", bansoukouDisabledFile.getFileName());
                    reconstructedCount++;
                }

            } catch (Exception e) {
                director.getLogger().logThrowable(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                        "CORE", e, "Failed to reconstruct tracking for mod %s", mod.offlineName());
            }
        }

        if (reconstructedCount > 0) {
            director.getLogger().log(ModDirectorSeverityLevel.INFO, LOG_DOMAIN,
                    "CORE", "Reconstructed tracking for %d existing mod file(s)", reconstructedCount);
            // Save the reconstructed tracking data
            tracker.save();
        } else {
            director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                    "CORE", "No existing mod files found to reconstruct tracking from");
        }
    }

    /**
     * Migrates existing mod files to the tracking system.
     * This method scans for mod files that exist on disk and match the configuration,
     * then adds them to the tracking system. This is useful for handling mods that
     * were installed before the tracking system was implemented.
     *
     * @param allInstallableMods All mods that are expected to be installed
     */
    private void migrateExistingModsToTracking(List<InstallableMod> allInstallableMods) {
        try {
            int migratedCount = 0;
            
            for (InstallableMod mod : allInstallableMods) {
                Path targetFile = mod.getTargetFile();
                if (targetFile == null) {
                    continue;
                }
                
                String fileName = targetFile.getFileName().toString();
                
                // Check if file exists on disk but is not being tracked
                if (Files.exists(targetFile) && !tracker.isTracked(fileName)) {
                    tracker.trackInstalledFile(targetFile);
                    director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                            "CORE", "Migrated existing mod to tracking: %s", fileName);
                    migratedCount++;
                }
                
                // Also check for disabled and bansoukou variants
                Path disabledFile = computeDisabledPath(targetFile);
                String disabledFileName = disabledFile.getFileName().toString();
                if (Files.exists(disabledFile) && !tracker.isTracked(disabledFileName)) {
                    tracker.trackInstalledFile(disabledFile);
                    director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                            "CORE", "Migrated existing disabled mod to tracking: %s", disabledFileName);
                    migratedCount++;
                }
                
                Path bansoukouPatchedFile = computeBansoukouPatchedPath(targetFile);
                String bansoukouPatchedFileName = bansoukouPatchedFile.getFileName().toString();
                if (Files.exists(bansoukouPatchedFile) && !tracker.isTracked(bansoukouPatchedFileName)) {
                    tracker.trackInstalledFile(bansoukouPatchedFile);
                    director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                            "CORE", "Migrated existing bansoukou patched mod to tracking: %s", bansoukouPatchedFileName);
                    migratedCount++;
                }
                
                Path bansoukouDisabledFile = computeBansoukouDisabledPath(targetFile);
                String bansoukouDisabledFileName = bansoukouDisabledFile.getFileName().toString();
                if (Files.exists(bansoukouDisabledFile) && !tracker.isTracked(bansoukouDisabledFileName)) {
                    tracker.trackInstalledFile(bansoukouDisabledFile);
                    director.getLogger().log(ModDirectorSeverityLevel.DEBUG, LOG_DOMAIN,
                            "CORE", "Migrated existing bansoukou disabled mod to tracking: %s", bansoukouDisabledFileName);
                    migratedCount++;
                }
            }
            
            if (migratedCount > 0) {
                director.getLogger().log(ModDirectorSeverityLevel.INFO, LOG_DOMAIN,
                        "CORE", "Migrated %d existing mod file(s) to tracking system", migratedCount);
                // Save the updated tracking data
                tracker.save();
            }
            
        } catch (Exception e) {
            director.getLogger().logThrowable(ModDirectorSeverityLevel.WARN, LOG_DOMAIN,
                    "CORE", e, "Failed to migrate existing mods to tracking");
        }
    }
}
