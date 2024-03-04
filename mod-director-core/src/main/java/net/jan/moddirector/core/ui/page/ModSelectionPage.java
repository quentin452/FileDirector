package net.jan.moddirector.core.ui.page;

import net.jan.moddirector.core.manage.select.InstallSelector;
import net.jan.moddirector.core.manage.select.SelectableInstallOption;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class ModSelectionPage extends JPanel {
    public ModSelectionPage(InstallSelector selector) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        JLabel titleLabel = new JLabel("Select files to download", SwingConstants.CENTER);
        titleLabel.setFont(new Font(titleLabel.getFont().getName(), Font.BOLD, 20));
        titleLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, titleLabel.getMinimumSize().height));
        add(titleLabel);

        selector.getSingleOptions().forEach(this::setupSingleOption);
        selector.getGroupOptions().forEach(this::setupGroupOption);
    }

    private void setupSingleOption(SelectableInstallOption option) {
        String borderText = option.getSource().startsWith("http") ? "URL" : option.getSource();
        JPanel optionPanel = new JPanel(new BorderLayout());
        optionPanel.setLayout(new BoxLayout(optionPanel, BoxLayout.Y_AXIS));
        optionPanel.setBorder(BorderFactory.createTitledBorder(borderText));

        String checkboxText = option.getSource().startsWith("http") ? option.getSource() : option.getName();
        JCheckBox installCheckBox = new JCheckBox(checkboxText, option.isSelected());
        installCheckBox.addItemListener(e -> option.setSelected(installCheckBox.isSelected()));
        optionPanel.add(installCheckBox);

        if(option.getDescription() != null) {
            optionPanel.add(new JLabel(asHtml(option.getDescription())));
        }

        add(optionPanel);
    }

    private void setupGroupOption(String groupName, List<SelectableInstallOption> options) {
        JPanel groupPanel = new JPanel();
        groupPanel.setLayout(new BoxLayout(groupPanel, BoxLayout.Y_AXIS));

        ButtonGroup group = new ButtonGroup();

        for(SelectableInstallOption option : options) {
            JPanel optionPanel = new JPanel();
            optionPanel.setLayout(new BoxLayout(optionPanel, BoxLayout.Y_AXIS));

            JRadioButton installRadioButton = new JRadioButton(option.getName(), option.isSelected());
            installRadioButton.addItemListener(e -> option.setSelected(installRadioButton.isSelected()));
            group.add(installRadioButton);
            optionPanel.add(installRadioButton);

            if(option.getDescription() != null) {
                optionPanel.add(new JLabel(asHtml(option.getDescription())));
            }

            groupPanel.add(optionPanel);
        }

        groupPanel.setBorder(BorderFactory.createTitledBorder(groupName));

        add(groupPanel);
    }

    private String asHtml(String content) {
        return "<html>" + content + "</html>";
    }
}
