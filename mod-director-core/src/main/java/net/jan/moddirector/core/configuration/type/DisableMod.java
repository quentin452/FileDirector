package net.jan.moddirector.core.configuration.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class DisableMod {
    private final String fileName;
    private final String folder;
    private final boolean delete;

    @JsonCreator
    public DisableMod(
        @JsonProperty(value = "fileName", required = true) String fileName,
        @JsonProperty(value = "folder", required = true) String folder,
        @JsonProperty(value = "delete") boolean delete
    ) {
        this.fileName = fileName;
        this.folder = folder;
        this.delete = delete;
    }

    public String getFileName() {
        return fileName;
    }

    public String getFolder() {
        return folder;
    }

    public boolean shouldDelete() {
        return delete;
    }
}