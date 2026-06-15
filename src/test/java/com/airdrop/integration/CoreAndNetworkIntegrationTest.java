package com.airdrop.integration;

import com.airdrop.domain.model.FileTask;
import com.airdrop.domain.model.Peer;
import com.airdrop.infrastructure.netty.NettyNetworkGateway;
import com.airdrop.usecase.DiscoverPeersUseCase;
import com.airdrop.usecase.SendFileUseCase;
import com.airdrop.usecase.port.in.FileTransferListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class CoreAndNetworkIntegrationTest {

    // Node 1 (Sender / Discoverer)
    private NettyNetworkGateway gateway1;
    private ScheduledExecutorService scheduler1;
    private DiscoverPeersUseCase discoverPeersUseCase1;
    private SendFileUseCase sendFileUseCase1;

    // Node 2 (Receiver / Broadcaster)
    private NettyNetworkGateway gateway2;
    private ScheduledExecutorService scheduler2;
    private DiscoverPeersUseCase discoverPeersUseCase2;

    @BeforeEach
    public void setUp() {
        // Node 1 Setup
        gateway1 = new NettyNetworkGateway();
        gateway1.setLocalPeerName("Integration-Node-1");
        scheduler1 = Executors.newSingleThreadScheduledExecutor();
        discoverPeersUseCase1 = new DiscoverPeersUseCase(gateway1, scheduler1, Clock.systemUTC());
        sendFileUseCase1 = new SendFileUseCase(gateway1, discoverPeersUseCase1);

        // Node 2 Setup
        gateway2 = new NettyNetworkGateway();
        gateway2.setLocalPeerName("Integration-Node-2");
        scheduler2 = Executors.newSingleThreadScheduledExecutor();
        discoverPeersUseCase2 = new DiscoverPeersUseCase(gateway2, scheduler2, Clock.systemUTC());
    }

    @AfterEach
    public void tearDown() {
        gateway1.shutdown();
        gateway2.shutdown();
        scheduler1.shutdownNow();
        scheduler2.shutdownNow();
    }

    @Test
    public void testEndToEndPeerDiscovery() throws Exception {
        // 1. Node 1 starts listening for peers via UseCase
        discoverPeersUseCase1.start();

        // 2. Node 2 starts broadcasting its presence via Gateway directly
        // Usually handled by another UseCase, but we simulate it here
        gateway2.startBroadcasting("Integration-Node-2", 9999);

        // 3. Wait for UDP multicast to reach Node 1
        boolean found = false;
        for (int i = 0; i < 10; i++) {
            List<Peer> activePeers = discoverPeersUseCase1.getActivePeers();
            if (activePeers.stream().anyMatch(p -> p.getName().equals("Integration-Node-2"))) {
                found = true;
                break;
            }
            Thread.sleep(500); // Poll every 500ms up to 5 seconds
        }

        assertTrue(found, "Node 1's DiscoverPeersUseCase should have registered Node 2");
    }

    @Test
    public void testEndToEndFileTransfer() throws Exception {
        // Create a dummy file for testing (1MB)
        File tempFile = File.createTempFile("e2e_transfer", ".dat");
        tempFile.deleteOnExit();
        byte[] originalData = new byte[1024 * 1024];
        new Random().nextBytes(originalData);
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(originalData);
        }

        CountDownLatch receiveLatch = new CountDownLatch(1);
        AtomicBoolean transferSuccess = new AtomicBoolean(false);

        // 1. Node 2 (Receiver) sets up global listener and starts TCP Server
        gateway2.setFileTransferListener(new FileTransferListener() {
            @Override
            public void onProgressUpdated(FileTask task) {
                if (task.getBytesTransferred() >= task.getFileSize()) {
                    try {
                        File receivedFile = new File(task.getFilePath());
                        if (receivedFile.exists() && receivedFile.length() == task.getFileSize()) {
                            byte[] receivedData = Files.readAllBytes(receivedFile.toPath());
                            assertArrayEquals(originalData, receivedData, "Received data must match sent data");
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
                System.err.println("Node-2 Receive Error: " + errorMessage);
                receiveLatch.countDown();
            }
        });
        gateway2.startServer(0); // Bind to random port
        int node2Port = gateway2.getBoundTcpPort();

        // 2. Node 2 broadcasts its presence, and Node 1 discovers it (Normal Cooperation)
        gateway2.startBroadcasting("Integration-Node-2", node2Port);
        discoverPeersUseCase1.start();

        Peer discoveredNode2 = null;
        for (int i = 0; i < 10; i++) {
            List<Peer> activePeers = discoverPeersUseCase1.getActivePeers();
            for (Peer p : activePeers) {
                if ("Integration-Node-2".equals(p.getName())) {
                    discoveredNode2 = p;
                    break;
                }
            }
            if (discoveredNode2 != null) break;
            Thread.sleep(500);
        }
        
        assertNotNull(discoveredNode2, "Node 1's DiscoverPeersUseCase should have registered Node 2");

        CountDownLatch sendLatch = new CountDownLatch(1);

        // 3. Node 1 uses SendFileUseCase to send the file to the discovered IP
        sendFileUseCase1.execute(discoveredNode2.getIp(), tempFile.getAbsolutePath(), new FileTransferListener() {
            @Override
            public void onProgressUpdated(FileTask task) {
                if (task.getBytesTransferred() >= task.getFileSize()) {
                    sendLatch.countDown();
                }
            }

            @Override
            public void onError(FileTask task, String errorMessage) {
                System.err.println("Node-1 Send Error: " + errorMessage);
                sendLatch.countDown();
            }
        });

        assertTrue(sendLatch.await(10, TimeUnit.SECONDS), "Send should complete within 10 seconds");
        assertTrue(receiveLatch.await(10, TimeUnit.SECONDS), "Receive should complete within 10 seconds");
        assertTrue(transferSuccess.get(), "File data integrity verification failed");
    }
}
