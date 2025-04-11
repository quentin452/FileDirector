package net.jan.moddirector.core.configuration;

import javax.swing.*;
import java.awt.*;

public class WarningDisplay {
    public static void show(String message) {
        JFrame frame = new JFrame("ModDirector Warning");
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setAlwaysOnTop(true);
        frame.setSize(450, 200);
        frame.setLocationRelativeTo(null);

        JTextArea textArea = new JTextArea(message);
        textArea.setEditable(false);
        textArea.setWrapStyleWord(true);
        textArea.setLineWrap(true);
        textArea.setOpaque(false);
        textArea.setFocusable(false);

        JButton button = new JButton("OK");
        button.addActionListener(e -> frame.dispose());

        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.add(textArea, BorderLayout.CENTER);
        panel.add(button, BorderLayout.SOUTH);

        frame.getContentPane().add(panel);
        frame.setVisible(true);

        // Block thread until dialog is closed
        while (frame.isDisplayable()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {}
        }
    }
}
