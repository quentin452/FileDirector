package net.jan.moddirector.core.configuration;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jan.moddirector.core.ModDirector;
import net.jan.moddirector.core.configuration.modpack.ModpackConfiguration;
import net.jan.moddirector.core.configuration.type.*;
import net.jan.moddirector.core.logging.ModDirectorSeverityLevel;
import net.jan.moddirector.core.manage.ModDirectorError;
import net.jan.moddirector.core.util.IOOperation;
import net.jan.moddirector.core.util.WebClient;
import net.jan.moddirector.core.util.WebGetResponse;

import java.io.*;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.io.FileUtils;

public class ConfigurationController {
    public static final ObjectMapper OBJECT_MAPPER = createObjectMapper();
    private static final String LOG_DOMAIN = "ModDirector/ConfigurationController";

    private static ObjectMapper createObjectMapper() {
        ObjectMapper instance = new ObjectMapper();
        instance.setDefaultLeniency(false);
        instance.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
        return instance;
    }

    private final ModDirector director;
    private final Path configurationDirectory;
    private final List<ModDirectorRemoteMod> configurations;

    private ModpackConfiguration modpackConfiguration;

    public ConfigurationController(ModDirector director, Path configurationDirectory) {
        this.director = director;
        this.configurationDirectory = configurationDirectory;
        this.configurations = new ArrayList<>();
    }

    public void load() {
        Path modpackConfigPath = configurationDirectory.resolve("modpack.json");
        if(Files.exists(modpackConfigPath) && !loadModpackConfiguration(modpackConfigPath)) {
                return;
        }

        try(Stream<Path> paths = Files.walk(configurationDirectory)) {
            paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().equals("modpack.json"))
                    .sorted()
                    .forEach(this::addConfig);
        } catch(IOException e) {
            director.getLogger().logThrowable(ModDirectorSeverityLevel.ERROR, LOG_DOMAIN,
                    "CORE", e, "Failed to iterate configuration directory!");
            director.addError(new ModDirectorError(ModDirectorSeverityLevel.ERROR,
                    "Failed to iterate configuration directory", e));
        }
    }

    private boolean loadModpackConfiguration(Path configurationPath) {
        try(InputStream stream = Files.newInputStream(configurationPath)) {
            modpackConfiguration = OBJECT_MAPPER.readValue(stream, ModpackConfiguration.class);
            return true;
        } catch(IOException e) {
            director.getLogger().logThrowable(ModDirectorSeverityLevel.ERROR, LOG_DOMAIN,
                    "CORE", e, "Failed to read modpack configuration!");
            director.addError(new ModDirectorError(ModDirectorSeverityLevel.ERROR,
                    "Failed to read modpack configuration!"));
            return false;
        }
    }

    private void addConfig(Path configurationPath) {
        String configString = configurationPath.toString();

        director.getLogger().log(ModDirectorSeverityLevel.INFO, LOG_DOMAIN,
                "CORE", "Loading config %s", configString);

        if(configString.endsWith(".remote.json")) {
            handleRemoteConfig(configurationPath);
        } else if(configString.endsWith(".bundle.json")) {
            handleBundleConfig(configurationPath);
        } else if(configString.endsWith(".modify.json")) {
            handleModifyConfig(configurationPath);
        } else {
            handleSingleConfig(configurationPath);
        }
    }

    private void handleRemoteConfig(Path configurationPath) {
        try(InputStream stream = Files.newInputStream(configurationPath)) {
            RemoteConfig remoteConfig = OBJECT_MAPPER.readValue(stream, RemoteConfig.class);
            try(WebGetResponse response = WebClient.get(remoteConfig.getUrl())) {
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                IOOperation.copy(response.getInputStream(), outputStream);
                String fileName = remoteConfig.getUrl().toString().substring(remoteConfig.getUrl().toString().lastIndexOf('/') + 1);
                Path installationRoot = director.getPlatform().installationRoot().toAbsolutePath().normalize();
                Path remoteConfigPath = installationRoot.resolve(configurationDirectory).resolve(fileName);
                Files.write(remoteConfigPath, outputStream.toByteArray());
                addConfig(remoteConfigPath);
                Files.delete(remoteConfigPath);
            } catch(UnknownHostException e) {
                director.getLogger().logThrowable(ModDirectorSeverityLevel.ERROR, LOG_DOMAIN,
                        "CORE", e, "Failed to resolve URL %s, skipping remote config...", remoteConfig.getUrl());
            }
        } catch(IOException e) {
            handleConfigException(e);
        }
    }

    private void handleBundleConfig(Path configurationPath) {
        try(InputStream stream = Files.newInputStream(configurationPath);
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            JsonObject jsonObject = new JsonParser().parse(reader).getAsJsonObject();

            JsonArray jsonArray = jsonObject.getAsJsonArray("curse");
            if(jsonArray != null) {
                for(JsonElement jsonElement : jsonArray) {
                    configurations.add(OBJECT_MAPPER.readValue(jsonElement.toString(), CurseRemoteMod.class));
                }
            }

            jsonArray = jsonObject.getAsJsonArray("modrinth");
            if(jsonArray != null) {
                for(JsonElement jsonElement : jsonArray) {
                    configurations.add(OBJECT_MAPPER.readValue(jsonElement.toString(), ModrinthRemoteMod.class));
                }
            }

            jsonArray = jsonObject.getAsJsonArray("url");
            if(jsonArray != null) {
                for(JsonElement jsonElement : jsonArray) {
                    configurations.add(OBJECT_MAPPER.readValue(jsonElement.toString(), UrlRemoteMod.class));
                }
            }

            jsonArray = jsonObject.getAsJsonArray("modify");
            if(jsonArray != null) {
                for(JsonElement jsonElement : jsonArray) {
                    ModifyMod modifyMod = OBJECT_MAPPER.readValue(jsonElement.toString(), ModifyMod.class);
                    handleModifyConfig(modifyMod);
                }
            }
        } catch(IOException e) {
            handleConfigException(e);
        }
    }

    private void handleSingleConfig(Path configurationPath) {
        Class<? extends ModDirectorRemoteMod> targetType = getTypeForFile(configurationPath);
        if(targetType != null) {
            try(InputStream stream = Files.newInputStream(configurationPath)) {
                configurations.add(OBJECT_MAPPER.readValue(stream, targetType));
            }
            catch(IOException e) {
                handleConfigException(e);
            }
        }
    }

    private void handleModifyConfig(Path configurationPath) {
        try(InputStream stream = Files.newInputStream(configurationPath)) {
            ModifyMod modifyMod = OBJECT_MAPPER.readValue(stream, ModifyMod.class);
            handleModifyConfig(modifyMod);
        } catch(IOException e) {
            handleConfigException(e);
        }
    }

    private void handleModifyConfig(ModifyMod modifyMod) {
        try {
            Path installationRoot = director.getPlatform().installationRoot().toAbsolutePath().normalize();
            Path modifyModFolderPath = installationRoot.resolve(modifyMod.getFolder());
            if(modifyMod.getFileName() == null) {
                if(Files.isDirectory(modifyModFolderPath) && modifyMod.shouldDelete()) {
                    director.getLogger().log(ModDirectorSeverityLevel.INFO, LOG_DOMAIN,
                            "CORE", "Deleting folder %s", modifyModFolderPath);
                    FileUtils.deleteDirectory(modifyModFolderPath.toFile());
                }
            } else {
                Path modifyModFilePath = modifyModFolderPath.resolve(modifyMod.getFileName());
                if(Files.isRegularFile(modifyModFilePath)) {
                    if(modifyMod.shouldDisable()){
                        director.getLogger().log(ModDirectorSeverityLevel.INFO, LOG_DOMAIN,
                                "CORE", "Disabling file %s", modifyModFilePath);
                        Files.move(modifyModFilePath, modifyModFilePath.resolveSibling(modifyMod.getFileName() + ".disabled-by-mod-director"));
                    } else if(modifyMod.shouldDelete()) {
                        director.getLogger().log(ModDirectorSeverityLevel.INFO, LOG_DOMAIN,
                                "CORE", "Deleting file %s", modifyModFilePath);
                        Files.delete(modifyModFilePath);
                    } else {
                        Path modifyModNewFilePath = null;
                        if(modifyMod.getNewFolder() != null) {
                            director.getLogger().log(ModDirectorSeverityLevel.INFO, LOG_DOMAIN,
                                    "CORE", "Moving file %s", modifyModFilePath);
                            modifyModFolderPath = installationRoot.resolve(modifyMod.getNewFolder());
                            Files.createDirectories(modifyModFolderPath);
                            modifyModNewFilePath = modifyModFolderPath.resolve(modifyMod.getFileName());
                        }
                        if(modifyMod.getNewFileName() != null) {
                            director.getLogger().log(ModDirectorSeverityLevel.INFO, LOG_DOMAIN,
                                    "CORE", "Renaming file %s", modifyModFilePath);
                            modifyModNewFilePath = modifyModNewFilePath != null // Moved before?
                                    ? modifyModNewFilePath.resolveSibling(modifyMod.getNewFileName()) // Yes -> Use new folder
                                    : modifyModFilePath.resolveSibling(modifyMod.getNewFileName()); // No -> Use old folder
                        }
                        if(modifyModNewFilePath != null) {
                            if(Files.exists(modifyModNewFilePath)) {
                                Path disabledFilePath = modifyModNewFilePath.resolveSibling(modifyModNewFilePath.getFileName() + ".disabled-by-mod-director");
                                if(Files.exists(disabledFilePath)) {
                                    Files.delete(disabledFilePath);
                                }
                                Files.move(modifyModNewFilePath, disabledFilePath);
                            }
                            Files.move(modifyModFilePath, modifyModNewFilePath);
                        }
                    }
                }
            }
        } catch(IOException e) {
            handleConfigException(e);
        }
    }

    private void handleConfigException(IOException e) {
        director.getLogger().logThrowable(ModDirectorSeverityLevel.ERROR, LOG_DOMAIN,
            "CORE", e, "Failed to " + (e instanceof JsonParseException ? "parse" : "open") + " a configuration for reading!");
        director.addError(new ModDirectorError(ModDirectorSeverityLevel.ERROR,
            "Failed to " + (e instanceof JsonParseException ? "parse" : "open") + " a configuration for reading", e));
    }

    private Class<? extends ModDirectorRemoteMod> getTypeForFile(Path file) {
        String name = file.toString();
        if(name.endsWith(".curse.json")) {
            return CurseRemoteMod.class;
        } else if(name.endsWith(".modrinth.json")) {
            return ModrinthRemoteMod.class;
        } else if(name.endsWith(".url.json")) {
            return UrlRemoteMod.class;
        } else {
            director.getLogger().log(ModDirectorSeverityLevel.WARN, LOG_DOMAIN,
                    "CORE", "Ignoring unknown json file %s", name);
            return null;
        }
    }

    public ModpackConfiguration getModpackConfiguration() {
        return modpackConfiguration;
    }

    public List<ModDirectorRemoteMod> getConfigurations() {
        return configurations;
    }
}
