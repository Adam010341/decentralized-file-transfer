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

    public void execute(String targetIp, String filePath, FileTransferListener listener) {
        // Step A: Peer Validation
        List<Peer> activePeers = discoverPeersUseCase.getActivePeers();
        Peer targetPeer = null;
        for (Peer peer : activePeers) {
            if (peer.getIp().equals(targetIp)) {
                targetPeer = peer;
                break;
            }
        }

        if (targetPeer == null) {
            throw new IllegalArgumentException(
                    "Target IP " + targetIp + " is not in the active peer registry or has timed out.");
        }

        // Step B: File Validation
        File file = new File(filePath);
        if (!file.exists() || file.isDirectory() || !file.canRead()) {
            throw new IllegalArgumentException("Invalid or unreadable file path: " + filePath);
        }

        // Step C: Entity Instantiation
        String taskId = UUID.randomUUID().toString();
        FileTask fileTask = new FileTask(taskId, file.getAbsolutePath(), file.getName(), file.length());

        // Step D: Dispatch
        networkGateway.sendFile(targetPeer, fileTask, listener);
    }
}
