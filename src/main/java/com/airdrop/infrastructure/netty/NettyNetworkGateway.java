package com.airdrop.infrastructure.netty;

import com.airdrop.domain.model.FileTask;
import com.airdrop.domain.model.Peer;
import com.airdrop.usecase.port.out.NetworkGateway;
import com.airdrop.usecase.port.in.PeerDiscoveryListener;
import com.airdrop.usecase.port.in.FileTransferListener;

import java.io.IOException;

public class NettyNetworkGateway implements NetworkGateway {

    private final NettyServer server;
    private final NettyClient client;
    
    private String localPeerName = "AirDrop-Node";
    private FileTransferListener fileTransferListener;

    public NettyNetworkGateway() {
        this.server = new NettyServer();
        this.client = new NettyClient();
    }

    public void setLocalPeerName(String localPeerName) {
        this.localPeerName = localPeerName;
    }

    public int getBoundTcpPort() {
        return server.getBoundTcpPort();
    }

    public void setFileTransferListener(FileTransferListener listener) {
        this.fileTransferListener = listener;
    }

    public void startBroadcasting(String localPeerName, int tcpPort) throws IOException {
        this.localPeerName = localPeerName;
        try {
            client.startUdpMulticastPublisher(localPeerName, tcpPort);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Failed to start UDP Multicast publisher", e);
        }
    }

    public void stopBroadcasting() {
        client.stopUdpPublisher();
    }

    @Override
    public void startDiscovery(PeerDiscoveryListener listener) {
        try {
            server.startUdpMulticastListener(listener);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void stopDiscovery() {
        server.stopUdpListener(); // Will add this method to NettyServer
    }

    public void startServer(int port) throws IOException {
        try {
            server.startTcpServer(port, fileTransferListener);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Failed to start TCP Server", e);
        }
    }

    public void stopServer() {
        server.stop();
    }

    @Override
    public void sendFile(Peer target, FileTask task, FileTransferListener listener) {
        client.sendFile(localPeerName, target, task, listener);
    }

    public void cancelTransfer(String taskId) {
        client.cancelTransfer(taskId);
    }

    public void shutdown() {
        server.stop();
        client.stop();
    }
}
