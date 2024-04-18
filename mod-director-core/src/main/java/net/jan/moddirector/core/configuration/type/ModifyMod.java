package net.jan.moddirector.core.configuration.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ModifyMod {
    private final String fileName;
    private final String folder;
    private final boolean disable;
    private final boolean delete;
    private final String newFolder;
    private final String newFileName;

    @JsonCreator
    public ModifyMod(
        @JsonProperty(value = "fileName") String fileName,
        @JsonProperty(value = "folder", required = true) String folder,
        @JsonProperty(value = "disable") boolean disable,
        @JsonProperty(value = "delete") boolean delete,
        @JsonProperty(value = "newFolder") String newFolder,
        @JsonProperty(value = "newFileName") String newFileName,
        @JsonProperty(value = "comment") String comment
    ) {
        this.fileName = fileName;
        this.folder = folder;
        this.disable = disable;
        this.delete = delete;
        this.newFolder = newFolder;
        this.newFileName = newFileName;
    }

    public String getFileName() {
        return fileName;
    }

    public String getFolder() {
        return folder;
    }

    public boolean shouldDisable() {
        return disable;
    }

    public boolean shouldDelete() {
        return delete;
    }

    public String getNewFolder() {
        return newFolder;
    }

    public String getNewFileName() {
        return newFileName;
    }
}
