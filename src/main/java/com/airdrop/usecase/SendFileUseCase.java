package com.airdrop.usecase;

import com.airdrop.domain.model.FileTask;
import com.airdrop.domain.model.Peer;
import com.airdrop.usecase.port.in.FileTransferListener;
import com.airdrop.usecase.port.out.NetworkGateway;

import java.io.File;
import java.util.List;
import java.util.UUID;

public class SendFileUseCase {

    private final NetworkGateway networkGateway;
    private final DiscoverPeersUseCase discoverPeersUseCase;

    public SendFileUseCase(NetworkGateway networkGateway, DiscoverPeersUseCase discoverPeersUseCase) {
        this.networkGateway = networkGateway;
        this.discoverPeersUseCase = discoverPeersUseCase;
    }

    public void execute(String peerId, String filePath, FileTransferListener listener) {
        // Step A: Peer Validation
        List<Peer> activePeers = discoverPeersUseCase.getActivePeers();
        Peer targetPeer = null;
        for (Peer peer : activePeers) {
            String currentPeerId = peer.getIp() + ":" + peer.getPort();
            if (currentPeerId.equals(peerId) || peer.getIp().equals(peerId)) {
                targetPeer = peer;
                break;
            }
        }

        if (targetPeer == null) {
            throw new IllegalArgumentException(
                    "Target " + peerId + " is not in the active peer registry or has timed out.");
        }

        // Step B: File Validation
        File file = new File(filePath);
        if (!file.exists() || !file.canRead()) {
            throw new IllegalArgumentException("Invalid or unreadable file path: " + filePath);
        }

        // Step C: Entity Instantiation & Auto Zip
        boolean isDirectory = file.isDirectory();
        File fileToSend = file;
        String fileNameToSend = file.getName();
        
        if (isDirectory) {
            try {
                // 將資料夾打包成 .zip 暫存檔
                fileToSend = File.createTempFile("airdrop_folder_", ".zip");
                fileToSend.deleteOnExit();
                
                System.out.printf("[*] 準備打包資料夾 \"%s\" ...%n", file.getName());
                
                com.airdrop.infrastructure.util.ZipUtil.zipDirectory(file, fileToSend, new com.airdrop.infrastructure.util.ZipUtil.ZipProgressListener() {
                    @Override
                    public void onProgress(long bytesProcessed, long totalBytes) {
                        if (totalBytes <= 0) return;
                        int percent = (int) ((double) bytesProcessed / totalBytes * 100);
                        int barLength = 20;
                        int filled = Math.min(barLength, percent * barLength / 100);
                        StringBuilder bar = new StringBuilder("[");
                        for (int i = 0; i < barLength; i++) {
                            bar.append(i < filled ? '█' : '░');
                        }
                        bar.append(']');
                        
                        System.out.printf("\r[打包中] %s %s %3d%%", file.getName(), bar.toString(), percent);
                    }
                });
                System.out.printf("%n[完成] 打包完畢，開始網路傳送...%n");
                
                fileNameToSend = file.getName() + ".zip";
            } catch (java.io.IOException e) {
                throw new RuntimeException("Failed to zip directory: " + e.getMessage(), e);
            }
        }

        String taskId = UUID.randomUUID().toString();
        FileTask fileTask = new FileTask(taskId, fileToSend.getAbsolutePath(), fileNameToSend, fileToSend.length(), isDirectory);

        // Step D: Dispatch
        networkGateway.sendFile(targetPeer, fileTask, listener);
    }
}
