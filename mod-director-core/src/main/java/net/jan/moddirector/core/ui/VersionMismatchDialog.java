package net.jan.moddirector.core.ui;

import java.awt.*;
import java.util.concurrent.CountDownLatch;
import javax.swing.*;

import net.minecraftforge.fml.exit.QualifiedExit;

import net.jan.moddirector.core.configuration.modpack.ModpackConfiguration;

public class VersionMismatchDialog extends JDialog {
    public VersionMismatchDialog(ModpackConfiguration configuration) throws InterruptedException
    {
        CountDownLatch latch = new CountDownLatch(1);
        JFrame frame = new JFrame(configuration.packName());
        JButton button = new JButton("Acknowledge");
        button.addActionListener(e -> {
            if(configuration.refuseLaunch()) {
                QualifiedExit.exit(0);
            }
            latch.countDown();
            frame.dispose();
        });
        String text = "Local and remote modpack versions are mismatching.";
        String textShutdown = text + " The game will now shut down!";
        JLabel label = new JLabel(configuration.refuseLaunch() ? this.asHtml(textShutdown) : this.asHtml(text), SwingConstants.CENTER);
        frame.add(label, BorderLayout.CENTER);
        frame.add(button, BorderLayout.SOUTH);
        frame.setSize(330, 120);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        latch.await();
    }

    private String asHtml(String content) {
        return "<html>" + content + "</html>";
    }
}
