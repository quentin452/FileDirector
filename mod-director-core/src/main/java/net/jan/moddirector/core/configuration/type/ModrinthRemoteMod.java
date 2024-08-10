package net.jan.moddirector.core.configuration.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import net.jan.moddirector.core.ModDirector;
import net.jan.moddirector.core.configuration.*;
import net.jan.moddirector.core.exception.ModDirectorException;
import net.jan.moddirector.core.manage.ProgressCallback;
import net.jan.moddirector.core.util.IOOperation;
import net.jan.moddirector.core.util.WebClient;
import net.jan.moddirector.core.util.WebGetResponse;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class ModrinthRemoteMod extends ModDirectorRemoteMod {
    private final String addonId;
    private final String fileId;
    private final String fileName;

    private ModrinthAddonFileInformation fileInformation;
    private String projectTitle;

    @JsonCreator
    public ModrinthRemoteMod(
            @JsonProperty(value = "addonId", required = true) String addonId,
            @JsonProperty(value = "fileId", required = true) String fileId,
            @JsonProperty(value = "metadata") RemoteModMetadata metadata,
            @JsonProperty(value = "installationPolicy") InstallationPolicy installationPolicy,
            @JsonProperty(value = "options") Map<String, Object> options,
            @JsonProperty(value = "folder") String folder,
            @JsonProperty(value = "inject") Boolean inject,
            @JsonProperty(value = "fileName") String fileName,
            @JsonProperty(value = "comment") String comment
    ) {
        super(metadata, installationPolicy, options, folder, inject);
        this.addonId = addonId;
        this.fileId = fileId;
        this.fileName = fileName;
    }

    @Override
    public String remoteType() {
        return "Modrinth";
    }

    @Override
    public String offlineName() {
        return "Project ID: " + addonId + ", File ID: " + fileId;
    }

    @Override
    public void performInstall(Path targetFile, ProgressCallback progressCallback, ModDirector director, RemoteModInformation information) throws ModDirectorException {
        try (WebGetResponse response = WebClient.get(new URL(fileInformation.files.get(0).url))) {
            progressCallback.setSteps(1);
            IOOperation.copy(response.getInputStream(), Files.newOutputStream(targetFile), progressCallback,
                    response.getStreamSize());
        } catch (IOException e) {
            throw new ModDirectorException("Failed to download file", e);
        }
    }

    @Override
    public RemoteModInformation queryInformation() throws ModDirectorException {
        queryTitle();
        try {
            URL apiUrl = new URL(String.format("https://api.modrinth.com/v2/project/%s/version/%s", addonId, fileId));
            fileInformation = ConfigurationController.OBJECT_MAPPER.readValue(apiUrl, ModrinthAddonFileInformation.class);
        } catch (MalformedURLException e) {
            throw new ModDirectorException("Failed to create Modrinth API URL", e);
        } catch (JsonParseException e) {
            throw new ModDirectorException("Failed to parse JSON response from Modrinth", e);
        } catch (JsonMappingException e) {
            throw new ModDirectorException("Failed to map JSON response from Modrinth, did they change their API?", e);
        } catch (IOException e) {
            throw new ModDirectorException("Failed to open connection to Modrinth", e);
        }

        String displayName = projectTitle != null ? projectTitle : (fileName != null ? fileName : fileInformation.files.get(0).filename);

        return new RemoteModInformation(displayName, fileInformation.files.get(0).filename);
    }

    private void queryTitle() throws ModDirectorException {
        try {
            URL projectUrl = new URL(String.format("https://api.modrinth.com/v2/project/%s", addonId));
            ModrinthProjectInformation projectInformation = ConfigurationController.OBJECT_MAPPER.readValue(projectUrl, ModrinthProjectInformation.class);
            projectTitle = projectInformation.title;
        } catch (MalformedURLException e) {
            throw new ModDirectorException("Failed to create Modrinth project URL", e);
        } catch (JsonParseException e) {
            throw new ModDirectorException("Failed to parse JSON response from Modrinth project", e);
        } catch (JsonMappingException e) {
            throw new ModDirectorException("Failed to map JSON response from Modrinth project", e);
        } catch (IOException e) {
            throw new ModDirectorException("Failed to open connection to Modrinth project", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ModrinthAddonFileInformation {
        @JsonProperty("files")
        private List<ModrinthFile> files;

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class ModrinthFile {
            @JsonProperty
            private String url;

            @JsonProperty
            private String filename;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ModrinthProjectInformation {
        @JsonProperty
        private String title;
    }
}
