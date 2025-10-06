package net.jan.moddirector.core.ui.page;

import net.jan.moddirector.core.manage.select.RemovalSelector;
import net.jan.moddirector.core.manage.select.SelectableRemovalOption;

import javax.swing.*;
import java.awt.*;

public class ModRemovalPage extends JPanel {
    public ModRemovalPage(RemovalSelector selector) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        JLabel titleLabel = new JLabel("Select old mods to remove", SwingConstants.CENTER);
        titleLabel.setFont(new Font(titleLabel.getFont().getName(), Font.BOLD, 20));
        titleLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, titleLabel.getMinimumSize().height));
        add(titleLabel);

        JLabel infoLabel = new JLabel("These mods are no longer in the configuration and can be safely removed:", SwingConstants.CENTER);
        infoLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, infoLabel.getMinimumSize().height));
        add(infoLabel);
        
        add(Box.createVerticalStrut(10));

        selector.getRemovalOptions().forEach(this::setupRemovalOption);
    }

    private void setupRemovalOption(SelectableRemovalOption option) {
        JPanel optionPanel = new JPanel(new BorderLayout());
        optionPanel.setLayout(new BoxLayout(optionPanel, BoxLayout.Y_AXIS));
        optionPanel.setBorder(BorderFactory.createTitledBorder("Old Mod"));

        JCheckBox removalCheckBox = new JCheckBox(option.getName(), option.isSelected());
        removalCheckBox.addItemListener(e -> option.setSelected(removalCheckBox.isSelected()));
        optionPanel.add(removalCheckBox);

        if(option.getDescription() != null) {
            optionPanel.add(new JLabel(asHtml(option.getDescription())));
        }

        add(optionPanel);
    }

    private String asHtml(String content) {
        return "<html>" + content + "</html>";
    }
}
