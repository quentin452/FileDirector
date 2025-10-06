package net.jan.moddirector.core.manage.select;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the selection of old mods to remove.
 * Similar to InstallSelector but for mod removal.
 */
public class RemovalSelector {
    private final List<SelectableRemovalOption> removalOptions;
    private final List<Path> oldModPaths;

    public RemovalSelector() {
        this.removalOptions = new ArrayList<>();
        this.oldModPaths = new ArrayList<>();
    }

    /**
     * Accept a list of old mod files and create selectable options for them.
     * 
     * @param oldMods List of paths to old mod files
     */
    public void accept(List<Path> oldMods) {
        if (oldMods == null || oldMods.isEmpty()) {
            return;
        }

        for (Path modPath : oldMods) {
            if (modPath != null) {
                String fileName = modPath.getFileName().toString();
                SelectableRemovalOption option = new SelectableRemovalOption(
                        true, // Selected by default
                        fileName,
                        "Old mod no longer in configuration",
                        modPath
                );
                removalOptions.add(option);
                oldModPaths.add(modPath);
            }
        }
    }

    /**
     * Check if there are any mods to remove.
     * 
     * @return true if there are mods that can be removed
     */
    public boolean hasModsToRemove() {
        return !removalOptions.isEmpty();
    }

    /**
     * Get all removal options for display.
     * 
     * @return List of selectable removal options
     */
    public List<SelectableRemovalOption> getRemovalOptions() {
        return removalOptions;
    }

    /**
     * Compute the list of mods to actually remove based on user selection.
     * 
     * @return List of paths to mods that should be removed
     */
    public List<Path> computeModsToRemove() {
        List<Path> modsToRemove = new ArrayList<>();
        for (SelectableRemovalOption option : removalOptions) {
            if (option.isSelected()) {
                modsToRemove.add(option.getModPath());
            }
        }
        return modsToRemove;
    }
}
