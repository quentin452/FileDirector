package net.jan.moddirector.core.ui;

import net.jan.moddirector.core.configuration.modpack.ModpackConfiguration;
import net.jan.moddirector.core.manage.select.InstallSelector;
import net.jan.moddirector.core.ui.page.ModSelectionPage;
import net.jan.moddirector.core.ui.page.ProgressPage;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.CountDownLatch;

public class SetupDialog extends JDialog {
    private static final int HEIGHT = 600;
    private static final int WIDTH = (int) (HEIGHT * /* golden ratio */ 1.618);

    private final ModpackConfiguration configuration;

    private final JButton nextButton;
    private CountDownLatch nextLatch;

    public SetupDialog(ModpackConfiguration configuration) {
        this.configuration = configuration;

        this.nextButton = new JButton("Next");
        this.nextButton.addActionListener(e -> nextLatch.countDown());

        setTitle(configuration.packName());
        setSize(WIDTH, HEIGHT);
    }

    public ProgressPage navigateToProgressPage(String title) {
        ProgressPage page = new ProgressPage(configuration, title);
        return updateContent(page, false);
    }

    public ModSelectionPage navigateToSelectionPage(InstallSelector installSelector) {
        ModSelectionPage page = new ModSelectionPage(installSelector);
        return updateContent(page, true);
    }

    private <T extends JPanel> T updateContent(T newContent, boolean hasNextButton) {
        JPanel wrapperPanel = new JPanel();
        wrapperPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        wrapperPanel.setLayout(new BoxLayout(wrapperPanel, BoxLayout.PAGE_AXIS));

        JScrollPane scrollPane = new JScrollPane(newContent);
        wrapperPanel.add(scrollPane);

        JPanel consentPanel = new JPanel();
        consentPanel.setMaximumSize(new Dimension(WIDTH, 30));
        consentPanel.setLayout(new BorderLayout());

        wrapperPanel.add(Box.createVerticalStrut(5));

        consentPanel.add(new JLabel("By checking the boxes above, you give consent to download the respective files!"), BorderLayout.WEST);
        wrapperPanel.add(consentPanel);

        if(hasNextButton) {
            consentPanel.add(nextButton, BorderLayout.EAST);
            nextButton.setAlignmentX(Component.RIGHT_ALIGNMENT);
            nextLatch = new CountDownLatch(1);
        } else {
            nextLatch = null;
        }

        setContentPane(wrapperPanel);
        revalidate();

        return newContent;
    }

    public void waitForNext() throws InterruptedException {
        if(nextLatch == null) {
            throw new IllegalStateException("Can't wait for a next press on a page which has no next button");
        }

        nextLatch.await();
    }
}
