package com.airdrop.infrastructure;

import com.airdrop.domain.model.FileTask;
import com.airdrop.domain.model.Peer;
import com.airdrop.infrastructure.netty.NettyNetworkGateway;
import com.airdrop.usecase.port.in.FileTransferListener;
import com.airdrop.usecase.port.in.PeerDiscoveryListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class NettyTest {

    private NettyNetworkGateway gateway1;
    private NettyNetworkGateway gateway2;

    @BeforeEach
    public void setUp() {
        gateway1 = new NettyNetworkGateway();
        gateway1.setLocalPeerName("Node-1");
        
        gateway2 = new NettyNetworkGateway();
        gateway2.setLocalPeerName("Node-2");
    }

    @AfterEach
    public void tearDown() {
        gateway1.shutdown();
        gateway2.shutdown();
    }

    @Test
    public void testTcpFileTransfer() throws Exception {
        // Create a dummy 1MB file
        File tempFile = File.createTempFile("test_transfer", ".dat");
        tempFile.deleteOnExit();
        byte[] originalData = new byte[1024 * 1024];
        new Random().nextBytes(originalData);
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(originalData);
        }

        CountDownLatch receiveLatch = new CountDownLatch(1);
        AtomicBoolean transferSuccess = new AtomicBoolean(false);

        // Setup global listener for receiver (Gateway 2)
        gateway2.setFileTransferListener(new FileTransferListener() {
            @Override
            public void onProgressUpdated(FileTask task) {
                if (task.getBytesTransferred() >= task.getFileSize()) {
                    System.out.println("Node-2 Receive completed: " + task.getFilePath());
                    try {
                        File receivedFile = new File(task.getFilePath());
                        if (receivedFile.exists() && receivedFile.length() == task.getFileSize()) {
                            byte[] receivedData = Files.readAllBytes(receivedFile.toPath());
                            assertArrayEquals(originalData, receivedData, "Received data should exactly match sent data");
                            receivedFile.delete();
                            transferSuccess.set(true);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        receiveLatch.countDown();
                    }
                }
            }

            @Override
            public void onError(FileTask task, String errorMessage) {
                System.err.println("Node-2 Receive error: " + errorMessage);
                receiveLatch.countDown();
            }
        });

        // Start Receiver
        gateway2.startServer(0);

        int receiverPort = gateway2.getBoundTcpPort();
        Peer target = new Peer("Node-2", "127.0.0.1", receiverPort);
        FileTask task = new FileTask("task-123", tempFile.getAbsolutePath(), tempFile.getName(), tempFile.length());

        CountDownLatch sendLatch = new CountDownLatch(1);

        // Start Sender (Gateway 1)
        gateway1.sendFile(target, task, new FileTransferListener() {
            @Override
            public void onProgressUpdated(FileTask task) {
                if (task.getBytesTransferred() >= task.getFileSize()) {
                    System.out.println("Node-1 Send completed.");
                    sendLatch.countDown();
                }
            }

            @Override
            public void onError(FileTask task, String errorMessage) {
                System.err.println("Node-1 Send error: " + errorMessage);
                sendLatch.countDown();
            }
        });

        assertTrue(sendLatch.await(5, TimeUnit.SECONDS), "File sending should complete within 5 seconds");
        assertTrue(receiveLatch.await(5, TimeUnit.SECONDS), "File receiving should complete within 5 seconds");
        assertTrue(transferSuccess.get(), "Transfer should be successful and data integrity verified");
    }

    @Test
    public void testUdpMulticastDiscovery() throws Exception {
        CountDownLatch discoverLatch = new CountDownLatch(1);
        AtomicBoolean discovered = new AtomicBoolean(false);

        // Gateway 2 listens for peers
        gateway2.startDiscovery(new PeerDiscoveryListener() {
            @Override
            public void onPeerDiscovered(Peer peer) {
                if ("Node-1".equals(peer.getName())) {
                    System.out.println("Node-2 discovered peer: " + peer.getName() + " on IP " + peer.getIp());
                    discovered.set(true);
                    discoverLatch.countDown();
                }
            }

            @Override
            public void onPeerLost(Peer peer) {
                // Not tested here
            }
        });

        // Gateway 1 broadcasts its presence
        gateway1.startBroadcasting("Node-1", 9999);

        // Wait up to 5 seconds for the UDP packet to be received
        boolean success = discoverLatch.await(5, TimeUnit.SECONDS);
        
        gateway1.stopBroadcasting();
        gateway2.stopDiscovery();
        
        assertTrue(success, "Node-2 should have discovered Node-1 via UDP Multicast");
    }
}
