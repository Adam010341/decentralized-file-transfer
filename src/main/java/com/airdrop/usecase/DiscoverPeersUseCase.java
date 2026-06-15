package com.airdrop.usecase;

import com.airdrop.domain.model.Peer;
import com.airdrop.usecase.port.in.PeerDiscoveryListener;
import com.airdrop.usecase.port.out.NetworkGateway;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class DiscoverPeersUseCase implements PeerDiscoveryListener {

    private final NetworkGateway networkGateway;
    private final ScheduledExecutorService scheduler;
    private final Clock clock;

    private final Map<String, Peer> activePeers = new ConcurrentHashMap<>();

    public DiscoverPeersUseCase(NetworkGateway networkGateway, ScheduledExecutorService scheduler, Clock clock) {
        this.networkGateway = networkGateway;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    private java.util.concurrent.ScheduledFuture<?> evictTask;

    public void start() {
        networkGateway.startDiscovery(this);
        if (evictTask == null || evictTask.isCancelled()) {
            evictTask = scheduler.scheduleAtFixedRate(this::evictStalePeers, 5, 5, TimeUnit.SECONDS);
        }
    }

    public void stop() {
        networkGateway.stopDiscovery();
        if (evictTask != null) {
            evictTask.cancel(false);
        }
        activePeers.clear();
    }

    public List<Peer> getActivePeers() {
        return new ArrayList<>(activePeers.values());
    }

    @Override
    public void onPeerDiscovered(Peer peer) {
        String key = getPeerKey(peer);
        activePeers.compute(key, (k, existingPeer) -> {
            if (existingPeer != null) {
                existingPeer.setLastSeen(clock.millis());
                return existingPeer;
            } else {
                peer.setLastSeen(clock.millis());
                return peer;
            }
        });
    }

    @Override
    public void onPeerLost(Peer peer) {
        activePeers.remove(getPeerKey(peer));
    }

    private void evictStalePeers() {
        long now = clock.millis();
        activePeers.values().removeIf(peer -> (now - peer.getLastSeen()) > 10000);
    }

    private String getPeerKey(Peer peer) {
        return peer.getIp() + ":" + peer.getPort();
    }
}