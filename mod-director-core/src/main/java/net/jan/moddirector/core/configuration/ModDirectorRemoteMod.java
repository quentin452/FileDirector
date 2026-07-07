package net.jan.moddirector.core.configuration;

import net.jan.moddirector.core.ModDirector;
import net.jan.moddirector.core.exception.ModDirectorException;
import net.jan.moddirector.core.manage.ProgressCallback;

import java.net.URL;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

public abstract class ModDirectorRemoteMod {
    private final RemoteModMetadata metadata;
    private final InstallationPolicy installationPolicy;
    private final Map<String, Object> options;
    private final String folder;
    private final boolean inject;

    public ModDirectorRemoteMod(
            RemoteModMetadata metadata,
            InstallationPolicy installationPolicy,
            Map<String, Object> options,
            String folder,
            Boolean inject
    ) {
        this.metadata = metadata;
        this.installationPolicy = installationPolicy == null ? new InstallationPolicy(
                false,
                null,
                null,
                null,
                null,
                false,
                false,
                false,
                null,
                null
        ) : installationPolicy;
        this.options = options == null ? Collections.emptyMap() : options;
        this.folder = folder;
        if(inject == null) {
            this.inject = folder == null;
        } else {
            this.inject = inject;
        }
    }

    public abstract String remoteType();
    public abstract String offlineName();

    public abstract RemoteModInformation queryInformation() throws ModDirectorException;
    public abstract void performInstall(Path targetFile, ProgressCallback progressCallback, ModDirector director,
            RemoteModInformation information) throws ModDirectorException;

    /**
     * Returns the concrete download URL that {@code --validate} should probe for reachability,
     * or {@code null} when this remote type has no separately-testable URL (in which case a
     * successful {@link #queryInformation()} is itself the fetchability proof — e.g. Curse, whose
     * api.curse.tools resolution already exercises the download path). Must be called AFTER
     * {@link #queryInformation()} so type-specific resolution state is populated.
     */
    public URL validationUrl(RemoteModInformation information) throws ModDirectorException {
        return null;
    }

    public RemoteModMetadata getMetadata() {
        return metadata;
    }

    public InstallationPolicy getInstallationPolicy() {
        return installationPolicy;
    }

    public Map<String, Object> getOptions() {
        return options;
    }
    
    public boolean forceInject() {
	    return inject;
    }
	
    public String getFolder() {
        return folder;
    }
}
