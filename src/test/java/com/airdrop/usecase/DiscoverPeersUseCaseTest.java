package com.airdrop.usecase;

import com.airdrop.domain.model.Peer;
import com.airdrop.usecase.port.out.NetworkGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class DiscoverPeersUseCaseTest {

    @Mock
    private NetworkGateway networkGateway;
    @Mock
    private ScheduledExecutorService scheduler;
    @Mock
    private Clock clock;

    private DiscoverPeersUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new DiscoverPeersUseCase(networkGateway, scheduler, clock);
    }

    @Test
    void testStart() {
        useCase.start();
        verify(networkGateway, times(1)).startDiscovery(useCase);
        verify(scheduler, times(1)).scheduleAtFixedRate(any(Runnable.class), eq(5L), eq(5L), eq(TimeUnit.SECONDS));
    }

    @Test
    void testStop() {
        java.util.concurrent.ScheduledFuture<?> future = mock(java.util.concurrent.ScheduledFuture.class);
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), eq(5L), eq(5L), eq(TimeUnit.SECONDS))).thenReturn((java.util.concurrent.ScheduledFuture)future);
        useCase.start();

        Peer peer = new Peer("NodeA", "192.168.1.10", 8080);
        when(clock.millis()).thenReturn(1000L);
        useCase.onPeerDiscovered(peer);

        useCase.stop();
        verify(networkGateway, times(1)).stopDiscovery();
        verify(future, times(1)).cancel(false);
        assertTrue(useCase.getActivePeers().isEmpty());
    }

    @Test
    void testOnPeerDiscovered_NewPeer() {
        when(clock.millis()).thenReturn(1000L);
        Peer peer = new Peer("NodeA", "192.168.1.10", 8080);
        
        useCase.onPeerDiscovered(peer);
        
        List<Peer> peers = useCase.getActivePeers();
        assertEquals(1, peers.size());
        assertEquals("192.168.1.10", peers.get(0).getIp());
        assertEquals(1000L, peers.get(0).getLastSeen());
    }

    @Test
    void testOnPeerDiscovered_ExistingPeer() {
        when(clock.millis()).thenReturn(1000L);
        Peer peer = new Peer("NodeA", "192.168.1.10", 8080);
        useCase.onPeerDiscovered(peer); // Added at T=1000

        when(clock.millis()).thenReturn(5000L);
        Peer samePeer = new Peer("NodeA", "192.168.1.10", 8080);
        useCase.onPeerDiscovered(samePeer); // Discovered again at T=5000
        
        List<Peer> peers = useCase.getActivePeers();
        assertEquals(1, peers.size(), "Should still be 1 peer");
        assertEquals(5000L, peers.get(0).getLastSeen(), "Last seen should be updated");
    }

    @Test
    void testOnPeerLost() {
        when(clock.millis()).thenReturn(1000L);
        Peer peer = new Peer("NodeA", "192.168.1.10", 8080);
        useCase.onPeerDiscovered(peer);
        assertEquals(1, useCase.getActivePeers().size());

        useCase.onPeerLost(peer);
        assertTrue(useCase.getActivePeers().isEmpty());
    }

    @Test
    void testEvictStalePeers() {
        // Capture the scheduled runnable so we can invoke it manually
        useCase.start();
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(runnableCaptor.capture(), eq(5L), eq(5L), eq(TimeUnit.SECONDS));
        Runnable evictTask = runnableCaptor.getValue();

        // Add peer 1 at T=1000
        when(clock.millis()).thenReturn(1000L);
        Peer peer1 = new Peer("Node1", "192.168.1.10", 8080);
        useCase.onPeerDiscovered(peer1);

        // Add peer 2 at T=5000
        when(clock.millis()).thenReturn(5000L);
        Peer peer2 = new Peer("Node2", "192.168.1.11", 8080);
        useCase.onPeerDiscovered(peer2);

        // Fast forward time to T=12000 (12 seconds later)
        // Peer 1 is 11000ms old (>10000), should be evicted
        // Peer 2 is 7000ms old (<10000), should stay
        when(clock.millis()).thenReturn(12000L);
        evictTask.run();

        List<Peer> activePeers = useCase.getActivePeers();
        assertEquals(1, activePeers.size());
        assertEquals("192.168.1.11", activePeers.get(0).getIp());
    }
}
