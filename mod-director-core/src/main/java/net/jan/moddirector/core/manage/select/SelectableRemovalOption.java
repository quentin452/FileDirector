package net.jan.moddirector.core.manage.select;

import java.nio.file.Path;

/**
 * Represents a selectable option for removing an old mod file.
 */
public class SelectableRemovalOption {
    private boolean selected;
    private final String name;
    private final String description;
    private final Path modPath;

    public SelectableRemovalOption(boolean selected, String name, String description, Path modPath) {
        this.selected = selected;
        this.name = name;
        this.description = description;
        this.modPath = modPath;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Path getModPath() {
        return modPath;
    }
}
