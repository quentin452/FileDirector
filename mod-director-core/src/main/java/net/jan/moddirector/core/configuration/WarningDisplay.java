package net.jan.moddirector.core.configuration;

import javax.swing.*;
import java.awt.*;

public class WarningDisplay {
	public static void show(String message) {
	    JFrame frame = new JFrame("ModDirector Warning");
	    frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
	    frame.setAlwaysOnTop(true);
	    frame.setLocationRelativeTo(null);

	    JTextArea textArea = new JTextArea(message);
	    textArea.setEditable(false);
	    textArea.setWrapStyleWord(true);
	    textArea.setLineWrap(true);
	    textArea.setOpaque(false);
	    textArea.setFocusable(false);

	    // Put the text area inside a scroll pane just in case
	    JScrollPane scrollPane = new JScrollPane(textArea);
	    scrollPane.setBorder(null);
	    scrollPane.setPreferredSize(new Dimension(600, Math.min(message.split("\n").length * 20, 500)));

	    JButton button = new JButton("OK");
	    button.addActionListener(e -> frame.dispose());

	    JPanel panel = new JPanel(new BorderLayout(10, 10));
	    panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
	    panel.add(scrollPane, BorderLayout.CENTER);
	    panel.add(button, BorderLayout.SOUTH);

	    frame.getContentPane().add(panel);
	    frame.pack(); // auto-resize to fit content
	    frame.setLocationRelativeTo(null); // center
	    frame.setVisible(true);

	    while (frame.isDisplayable()) {
	        try {
	            Thread.sleep(100);
	        } catch (InterruptedException ignored) {}
	    }
	}
}
