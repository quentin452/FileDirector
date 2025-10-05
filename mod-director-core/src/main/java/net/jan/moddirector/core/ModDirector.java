package net.jan.moddirector.core;

import net.jan.moddirector.core.configuration.ModDirectorRemoteMod;
import net.jan.moddirector.core.configuration.modpack.ModpackConfiguration;
import net.jan.moddirector.core.logging.ModDirectorSeverityLevel;
import net.jan.moddirector.core.logging.ModDirectorLogger;
import net.jan.moddirector.core.configuration.ConfigurationController;
import net.jan.moddirector.core.manage.InstallController;
import net.jan.moddirector.core.manage.InstalledModsTracker;
import net.jan.moddirector.core.manage.NullProgressCallback;
import net.jan.moddirector.core.manage.ProgressCallback;
import net.jan.moddirector.core.manage.install.InstallableMod;
import net.jan.moddirector.core.manage.install.InstalledMod;
import net.jan.moddirector.core.manage.ModDirectorError;
import net.jan.moddirector.core.manage.select.InstallSelector;
import net.jan.moddirector.core.platform.ModDirectorPlatform;
import net.jan.moddirector.core.ui.SetupDialog;
import net.jan.moddirector.core.ui.VersionMismatchDialog;
import net.jan.moddirector.core.ui.page.ProgressPage;
import net.jan.moddirector.core.util.WebClient;
import net.jan.moddirector.core.util.WebGetResponse;
import net.minecraftforge.fml.exit.QualifiedExit;
import org.apache.commons.io.FileUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.*;

public class ModDirector {
    private static ModDirector instance;

    public static ModDirector bootstrap(ModDirectorPlatform platform) {
        if(instance != null) {
            throw new IllegalStateException("ModDirector has already been bootstrapped using platform " +
                    platform.name());
        }

        instance = new ModDirector(platform);
        instance.bootstrap();
        return instance;
    }

    public static ModDirector getInstance() {
        if(instance == null) {
            throw new IllegalStateException("ModDirector has not been bootstrapped yet");
        }

        return instance;
    }

    private final ModDirectorPlatform platform;
    private final ModDirectorLogger logger;
    private final ConfigurationController configurationController;
    private final InstallController installController;
    private final InstallSelector installSelector;
    private final List<ModDirectorError> errors;
    private final List<InstalledMod> installedMods;
    private final ExecutorService executorService;
    private final NullProgressCallback nullProgressCallback;
    private final InstalledModsTracker installedModsTracker;
    private String modpackRemoteVersion;

    private ModDirector(ModDirectorPlatform platform) {
        this.platform = platform;
        this.logger = platform.logger();

        this.configurationController = new ConfigurationController(this, platform.configurationDirectory());
        this.installedModsTracker = new InstalledModsTracker(this);
        this.installController = new InstallController(this, installedModsTracker);
        this.installSelector = new InstallSelector();

        this.errors = new LinkedList<>();
        this.installedMods = new LinkedList<>();
        this.executorService = Executors.newFixedThreadPool(4);

        this.nullProgressCallback = new NullProgressCallback();

        this.modpackRemoteVersion = null;

        try (InputStream is = this.getClass().getResource("/cacerts").openStream()) {
            File cacertsCopy = File.createTempFile("cacerts", "");
            cacertsCopy.deleteOnExit();
            FileUtils.copyInputStreamToFile(is, cacertsCopy);
            System.setProperty("javax.net.ssl.trustStore", cacertsCopy.getAbsolutePath());
            logger.log(ModDirectorSeverityLevel.WARN, "ModDirector", "CORE", "Successfully replaced CA certificates with updated ones!");
        } catch (Exception e) {
            logger.log(ModDirectorSeverityLevel.WARN, "ModDirector", "CORE", "Unable to replace CA certificates!");
        }

        logger.log(ModDirectorSeverityLevel.INFO, "ModDirector", "CORE", "Mod director loaded!");
    }

    private void bootstrap() {
        platform.bootstrap();
    }

    private ProgressCallback createNullProgressCallback(String title, String info) {
        return nullProgressCallback;
    }

    public boolean activate(long timeout, TimeUnit timeUnit) throws InterruptedException, IOException {
        configurationController.load();
        List<ModDirectorRemoteMod> mods = configurationController.getConfigurations();
        ModpackConfiguration modpackConfiguration = configurationController.getModpackConfiguration();

        if(modpackConfiguration == null) {
            logger.log(ModDirectorSeverityLevel.WARN, "ModDirector", "CORE",
                    "This modpack does not contain a modpack.json, if you are the author, consider adding one!");
            modpackConfiguration = ModpackConfiguration.createDefault();
        } else if(modpackConfiguration.remoteVersion() != null) {
            try(WebGetResponse response = WebClient.get(modpackConfiguration.remoteVersion());
                BufferedReader reader = new BufferedReader(new InputStreamReader(response.getInputStream(), StandardCharsets.UTF_8))) {
                modpackRemoteVersion = reader.readLine();
            }
        }

        if(hasFatalError()) {
            return false;
        }

        SetupDialog setupDialog = null;
        if(!platform.headless()) {
            setupDialog = new SetupDialog(modpackConfiguration);
            setupDialog.setLocationRelativeTo(null);
            setupDialog.setVisible(true);
        }

        ProgressPage preInstallationPage = setupDialog == null ? null
                : setupDialog.navigateToProgressPage("Checking installation...");

        List<ModDirectorRemoteMod> excludedMods = new ArrayList<>();
        List<InstallableMod> reInstalls = new ArrayList<>();
        List<InstallableMod> freshInstalls = new ArrayList<>();
        List<Callable<Void>> preInstallTasks = installController.createPreInstallTasks(
                mods,
                excludedMods,
                freshInstalls,
                reInstalls,
                preInstallationPage != null ?
                        preInstallationPage::createProgressCallback :
                        this::createNullProgressCallback
        );

        awaitAll(executorService.invokeAll(preInstallTasks));
        installSelector.accept(excludedMods, freshInstalls, reInstalls);

        if(hasFatalError()) {
            errorExit();
        }

        // Clean up mods that are no longer in the configuration
        // This must be done BEFORE installation to remove old files before they get overwritten
        logger.log(ModDirectorSeverityLevel.INFO, "ModDirector", "CORE", "Cleaning up old mod files...");
        installController.cleanupOldMods(mods, freshInstalls, reInstalls);

        if(setupDialog != null && installSelector.hasSelectableOptions()) {
            setupDialog.navigateToSelectionPage(installSelector);
            setupDialog.waitForNext();
        }

        List<InstallableMod> toInstall = installSelector.computeModsToInstall();
        ProgressPage installProgressPage = setupDialog == null ? null :
                setupDialog.navigateToProgressPage("Installing " + modpackConfiguration.packName());

        List<Callable<Void>> installTasks = installController.createInstallTasks(
                toInstall,
                installProgressPage != null ?
                        installProgressPage::createProgressCallback :
                        this::createNullProgressCallback
        );

        installTasks.add(() -> {
            installController.markDisabledMods(installSelector.computeDisabledMods());
            return null;
        });

        awaitAll(executorService.invokeAll(installTasks));

        if(hasFatalError()) {
            errorExit();
        }

        // Save the tracker after all installations are complete
        installedModsTracker.save();

        executorService.shutdown();
        executorService.awaitTermination(timeout, timeUnit);

        if(setupDialog != null) {
            setupDialog.dispose();
        }

        VersionMismatchDialog versionMismatchDialog = null;

        if(modpackConfiguration.remoteVersion() != null && modpackConfiguration.localVersion() != null && !modpackRemoteVersion.contains(modpackConfiguration.localVersion())) {
            logger.log(ModDirectorSeverityLevel.ERROR, "ModDirector", "CORE",
                "Modpack version mismatch!");
            if (!platform.headless()) {
                versionMismatchDialog = new VersionMismatchDialog(modpackConfiguration);
            }
        }

        if(versionMismatchDialog != null) {
            versionMismatchDialog.dispose();
        }

        return !hasFatalError();
    }

    public ModDirectorLogger getLogger() {
        return logger;
    }

    public ModDirectorPlatform getPlatform() {
        return platform;
    }

    public ConfigurationController getConfigurationController() {
        return configurationController;
    }

    public String getModpackRemoteVersion() {
        return modpackRemoteVersion;
    }

    public void addError(ModDirectorError error) {
        synchronized(errors) {
            errors.add(error);
        }
    }

    public boolean hasFatalError() {
        return errors.stream().anyMatch(e -> e.getLevel() == ModDirectorSeverityLevel.ERROR);
    }

    public void installSuccess(InstalledMod mod) {
        synchronized(installedMods) {
            installedMods.add(mod);
        }
    }

    public List<InstalledMod> getInstalledMods() {
        return Collections.unmodifiableList(installedMods);
    }

    public void errorExit() {
        logger.log(ModDirectorSeverityLevel.ERROR, "ModDirector", "CORE",
                "============================================================");
        logger.log(ModDirectorSeverityLevel.ERROR, "ModDirector", "CORE",
                "Summary of %d encountered errors:", errors.size());
        errors.forEach(e -> {
            if(e.getException() != null) {
                logger.logThrowable(e.getLevel(), "ModDirector", "CORE", e.getException(), e.getMessage());
            } else {
                logger.log(e.getLevel(), "ModDirector", "CORE", e.getMessage());
            }
        });
        logger.log(ModDirectorSeverityLevel.ERROR, "ModDirector", "CORE",
                "============================================================");
        QualifiedExit.exit(1);
    }

    private void awaitAll(List<Future<Void>> futures) throws InterruptedException {
        for(Future<Void> future : futures) {
            try {
                future.get();
            } catch (CancellationException e) {
                logger.logThrowable(
                        ModDirectorSeverityLevel.ERROR,
                        "ModDirector",
                        "CORE",
                        e,
                        "A future task was cancelled unexpectedly"
                );
                addError(new ModDirectorError(
                        ModDirectorSeverityLevel.ERROR,
                        "A future task was cancelled unexpectedly",
                        e
                ));
            } catch (ExecutionException e) {
                logger.logThrowable(
                        ModDirectorSeverityLevel.ERROR,
                        "ModDirector",
                        "CORE",
                        e,
                        "An exception occurred while performing asynchronous work"
                );
                addError(new ModDirectorError(
                        ModDirectorSeverityLevel.ERROR,
                        "An exception occurred while performing asynchronous work",
                        e
                ));
            }
        }
    }
}
