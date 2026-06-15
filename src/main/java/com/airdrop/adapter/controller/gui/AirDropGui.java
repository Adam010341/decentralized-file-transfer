package com.airdrop.adapter.controller.gui;

import com.airdrop.domain.model.FileTask;
import com.airdrop.domain.model.Peer;
import com.airdrop.usecase.DiscoverPeersUseCase;
import com.airdrop.usecase.SendFileUseCase;
import com.airdrop.usecase.port.in.FileTransferListener;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.List;

public class AirDropGui extends JFrame {

    private JComboBox<String> targetComboBox;
    private JButton discoverButton;
    private JButton selectFileButton;
    private JLabel filePathLabel;
    private JButton sendButton;
    private JProgressBar progressBar;

    private String selectedFilePath;
    private final DiscoverPeersUseCase discoverPeersUseCase;
    private final SendFileUseCase sendFileUseCase;

    public AirDropGui(DiscoverPeersUseCase discoverPeersUseCase, SendFileUseCase sendFileUseCase) {
        this.discoverPeersUseCase = discoverPeersUseCase;
        this.sendFileUseCase = sendFileUseCase;
        
        setTitle("AirDrop P2P Transfer");
        setSize(550, 220);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new GridBagLayout());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Row 1: Target IP Input / Discover
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0.2;
        add(new JLabel("Target Device:"), gbc);

        JPanel targetPanel = new JPanel(new BorderLayout(5, 0));
        targetComboBox = new JComboBox<>();
        targetComboBox.setEditable(true); // Allow manual IP entry
        targetPanel.add(targetComboBox, BorderLayout.CENTER);

        discoverButton = new JButton("🔍 Discover");
        targetPanel.add(discoverButton, BorderLayout.EAST);

        gbc.gridx = 1;
        gbc.weightx = 0.8;
        add(targetPanel, gbc);

        // Row 2: File Selection
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0.2;
        selectFileButton = new JButton("Select File / Folder");
        add(selectFileButton, gbc);

        gbc.gridx = 1;
        gbc.weightx = 0.8;
        filePathLabel = new JLabel("No file selected");
        add(filePathLabel, gbc);

        // Row 3: Send Button
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        sendButton = new JButton("Send");
        add(sendButton, gbc);

        // Row 4: Progress Bar
        gbc.gridy = 3;
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setString("Ready");
        add(progressBar, gbc);

        // Event Listeners
        discoverButton.addActionListener(e -> initiateDiscovery());

        selectFileButton.addActionListener(e -> {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            int result = fileChooser.showOpenDialog(this);
            if (result == JFileChooser.APPROVE_OPTION) {
                File selectedFile = fileChooser.getSelectedFile();
                selectedFilePath = selectedFile.getAbsolutePath();
                filePathLabel.setText(selectedFilePath);
            }
        });

        sendButton.addActionListener(e -> initiateTransfer());
    }

    private void initiateDiscovery() {
        discoverButton.setEnabled(false);
        targetComboBox.removeAllItems();
        targetComboBox.addItem("Searching...");
        targetComboBox.setEnabled(false);

        // Start UDP broadcast
        discoverPeersUseCase.start();

        // Wait 1.5 seconds in the background to collect peers, then update UI
        Timer timer = new Timer(1500, e -> {
            targetComboBox.removeAllItems();
            List<Peer> peers = discoverPeersUseCase.getActivePeers();
            
            if (peers.isEmpty()) {
                targetComboBox.addItem("No devices found");
            } else {
                for (Peer peer : peers) {
                    // Format: "Name (192.168.1.1:8080)" -> IP is extractable
                    String name = peer.getName() != null ? peer.getName() : "Unknown";
                    targetComboBox.addItem(name + " (" + peer.getIp() + ":" + peer.getPort() + ")");
                }
            }
            
            targetComboBox.setEnabled(true);
            discoverButton.setEnabled(true);
        });
        timer.setRepeats(false);
        timer.start();
    }

    private void initiateTransfer() {
        Object selectedItem = targetComboBox.getSelectedItem();
        if (selectedItem == null) return;
        
        String selection = selectedItem.toString().trim();
        String ip = "";

        // Extract IP:Port if format is "Name (192.168.1.1:8080)"
        if (selection.contains("(") && selection.contains(")")) {
            ip = selection.substring(selection.lastIndexOf("(") + 1, selection.lastIndexOf(")")).trim();
            // 不再移除 Port，保留 IP:Port 格式以精確匹配目標
        } else {
            // Direct IP entry
            ip = selection;
        }

        if (ip.isEmpty() || ip.equals("Searching...") || ip.equals("No devices found") || selectedFilePath == null) {
            JOptionPane.showMessageDialog(this, "Please select a valid Target Device and a file.", "Validation Error", JOptionPane.WARNING_MESSAGE);
            return;
        }

        sendButton.setEnabled(false);
        selectFileButton.setEnabled(false);
        targetComboBox.setEnabled(false);
        discoverButton.setEnabled(false);
        
        progressBar.setValue(0);
        progressBar.setString("Preparing...");

        final String targetIp = ip;

        try {
            sendFileUseCase.execute(targetIp, selectedFilePath, new FileTransferListener() {
                @Override
                public void onProgressUpdated(FileTask task) {
                    SwingUtilities.invokeLater(() -> {
                        long total = task.getFileSize();
                        long transferred = task.getBytesTransferred();
                        
                        if (total > 0) {
                            int percent = (int) ((double) transferred / total * 100);
                            progressBar.setValue(percent);
                            progressBar.setString(percent + "%");
                        }

                        if (task.getStatus() == FileTask.Status.COMPLETED) {
                            progressBar.setValue(100);
                            progressBar.setString("Completed Successfully");
                            resetUiState();
                        }
                    });
                }

                @Override
                public void onError(FileTask task, String errorMessage) {
                    SwingUtilities.invokeLater(() -> {
                        progressBar.setString("Failed");
                        JOptionPane.showMessageDialog(AirDropGui.this, "Transfer Failed: " + errorMessage, "Error", JOptionPane.ERROR_MESSAGE);
                        resetUiState();
                    });
                }
            });
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "An unexpected error occurred: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            resetUiState();
        }
    }

    private void resetUiState() {
        sendButton.setEnabled(true);
        selectFileButton.setEnabled(true);
        targetComboBox.setEnabled(true);
        discoverButton.setEnabled(true);
    }
}
