package com.airdrop.usecase.port.out;

import com.airdrop.domain.model.FileTask;
import com.airdrop.domain.model.Peer;
import com.airdrop.usecase.port.in.PeerDiscoveryListener;
import com.airdrop.usecase.port.in.FileTransferListener;

public interface NetworkGateway {
    void startDiscovery(PeerDiscoveryListener listener);
    void stopDiscovery();
    void sendFile(Peer target, FileTask task, FileTransferListener listener);
}
