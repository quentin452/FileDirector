package net.jan.moddirector.standalone;

import net.jan.moddirector.core.ModDirector;
import net.jan.moddirector.core.configuration.ModDirectorRemoteMod;
import net.jan.moddirector.core.configuration.RemoteModInformation;
import net.jan.moddirector.core.configuration.RemoteModMetadata;
import net.jan.moddirector.core.exception.ModDirectorException;
import net.jan.moddirector.core.platform.PlatformSide;
import net.jan.moddirector.core.util.WebClient;
import net.jan.moddirector.core.util.WebGetResponse;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Native fetchability audit for a mod-director bundle directory. Loads every bundle entry through
 * FileDirector's OWN {@code ConfigurationController} / remote-resolution logic (so it cannot diverge
 * from how the mod actually installs), resolves each remote, probes its download URL, and reports a
 * PASS/FAIL contract compatible with the external {@code bundle_check.py} preflight.
 */
public final class ModDirectorValidator {
    private static final int PARALLELISM = 16;

    private ModDirectorValidator() {
    }

    public static int run(Path configDir) throws Exception {
        if(!Files.isDirectory(configDir)) {
            System.err.println("validate: config dir not found: " + configDir);
            return 2;
        }

        // Isolated, empty installation root: ConfigurationController.load() also executes any
        // .modify / inline "modify" operations, which only touch files that exist under the
        // installation root. Pointing it at a throwaway temp dir makes those side-effects inert
        // so --validate never mutates the caller's working tree.
        Path sandboxRoot = Files.createTempDirectory("fd-validate-root");
        sandboxRoot.toFile().deleteOnExit();

        ModDirectorStandalonePlatform platform = new ModDirectorStandalonePlatform(configDir, sandboxRoot);
        ModDirector director = ModDirector.bootstrap(platform);

        director.getConfigurationController().load();
        List<ModDirectorRemoteMod> mods = director.getConfigurationController().getConfigurations();

        System.out.println("[validate] " + mods.size() + " bundle entries in " + configDir);
        System.out.println("============================================================");

        ExecutorService pool = Executors.newFixedThreadPool(PARALLELISM);
        List<Future<Result>> futures = new ArrayList<>();
        for(ModDirectorRemoteMod mod : mods) {
            futures.add(pool.submit(() -> validateOne(mod)));
        }

        int failures = 0;
        for(Future<Result> future : futures) {
            Result result = future.get();
            System.out.println(result.line());
            if(!result.ok) {
                failures++;
            }
        }
        pool.shutdownNow();

        System.out.println("============================================================");
        if(failures == 0) {
            System.out.println("PASS");
            return 0;
        }
        System.out.println("FAIL (" + failures + " unreachable)");
        return 1;
    }

    private static Result validateOne(ModDirectorRemoteMod mod) {
        String label = mod.remoteType() + " " + mod.offlineName() + " (side=" + sideLabel(mod) + ")";
        try {
            RemoteModInformation information = mod.queryInformation();
            URL url = mod.validationUrl(information);
            if(url != null) {
                // Open the exact GET path the mod uses to download. A returned stream means the
                // server answered 2xx (WebClient follows redirects); we close it without reading the
                // body. A null url (e.g. Curse with no direct URL) is treated as reachable because the
                // preceding queryInformation() already exercised the resolution path (BUG-010 lesson).
                try(WebGetResponse response = WebClient.get(url)) {
                    response.getInputStream();
                }
            }
            return new Result(true, label, null);
        } catch(ModDirectorException | IOException | RuntimeException e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new Result(false, label, reason);
        }
    }

    private static String sideLabel(ModDirectorRemoteMod mod) {
        RemoteModMetadata metadata = mod.getMetadata();
        PlatformSide side = metadata == null ? null : metadata.getSide();
        if(side == PlatformSide.CLIENT) {
            return "CLIENT";
        }
        if(side == PlatformSide.SERVER) {
            return "SERVER";
        }
        return "BOTH";
    }

    private static final class Result {
        private final boolean ok;
        private final String label;
        private final String reason;

        private Result(boolean ok, String label, String reason) {
            this.ok = ok;
            this.label = label;
            this.reason = reason;
        }

        private String line() {
            if(ok) {
                return "[OK]   " + label;
            }
            return "[FAIL] " + label + " -> " + reason;
        }
    }
}
