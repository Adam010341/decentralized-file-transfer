package com.airdrop;

import com.airdrop.adapter.controller.CliController;
import com.airdrop.infrastructure.cli.PicocliRunner;
import com.airdrop.infrastructure.netty.NettyNetworkGateway;
import com.airdrop.usecase.DiscoverPeersUseCase;
import com.airdrop.usecase.SendFileUseCase;
import com.airdrop.usecase.port.in.FileTransferListener;
import com.airdrop.domain.model.FileTask;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * App — Composition Root（依賴組裝根）
 *
 * <p>
 * <b>這個類別在 Clean Architecture 中的角色：</b>
 * </p>
 * <p>
 * {@code App.main()} 是整個應用程式唯一知道「所有具體實作」的地方。
 * 它負責將各層的物件組裝在一起（Dependency Injection by Hand），
 * 然後將控制權交給 {@link PicocliRunner}。
 * </p>
 *
 * <p>
 * <b>依賴組裝順序（由內到外）：</b>
 * </p>
 * <pre>
 *   1. Domain          — Peer, FileTask（無外部依賴）
 *   2. Infrastructure  — NettyNetworkGateway（實作 NetworkGateway 介面）
 *   3. Use Cases       — DiscoverPeersUseCase, SendFileUseCase
 *   4. Adapters        — CliController
 *   5. Frameworks      — PicocliRunner
 *   6. Entry Point     — App.main() → PicocliRunner.run(args) → System.exit(code)
 * </pre>
 *
 * <p>
 * <b>為什麼 System.exit() 在 App 而不在 PicocliRunner？</b>
 * </p>
 * <p>
 * 將 {@code System.exit()} 集中在 main() 讓 {@link PicocliRunner} 可被單元測試：
 * 測試時呼叫 {@code runner.run(args)} 取得 exit code，不會中斷 JVM。
 * </p>
 */
public class App {

    public static void main(String[] args) {

        // ── Step 1: 建立 Infrastructure 層物件 ──────────────────────────────
        NettyNetworkGateway gateway = new NettyNetworkGateway();
        String localName = resolveLocalHostname();
        gateway.setLocalPeerName(localName);

        // TCP Server：監聽接收端傳入的檔案（port 0 = 作業系統隨機分配）
        try {
            // 設定接收檔案時的進度 listener（印至 stdout）
            gateway.setFileTransferListener(buildReceiveListener());
            gateway.startServer(0);
            System.out.println("[*] TCP Server 啟動，監聽 port " + gateway.getBoundTcpPort());
        } catch (IOException e) {
            System.err.println("[錯誤] 無法啟動 TCP Server：" + e.getMessage());
            System.exit(1);
        }

        // UDP Multicast Broadcast：讓其他節點能發現本機
        try {
            gateway.startBroadcasting(localName, gateway.getBoundTcpPort());
            System.out.println("[*] UDP Broadcast 啟動，裝置名稱：" + localName);
        } catch (IOException e) {
            System.err.println("[警告] UDP Broadcast 啟動失敗（其他人無法發現本機）：" + e.getMessage());
            // 非致命錯誤，繼續執行
        }

        // 關閉鉤子：JVM 結束時清理 Netty 資源
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "peer-eviction-scheduler");
            t.setDaemon(true); // daemon thread 不阻止 JVM 結束
            return t;
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[*] 正在關閉...");
            gateway.shutdown();
            scheduler.shutdownNow();
        }, "shutdown-hook"));

        // ── Step 2: 建立 Use Case 層物件 ────────────────────────────────────
        DiscoverPeersUseCase discoverPeersUseCase =
                new DiscoverPeersUseCase(gateway, scheduler, Clock.systemUTC());
        SendFileUseCase sendFileUseCase =
                new SendFileUseCase(gateway, discoverPeersUseCase);

        // ── Step 3: 建立 Adapter 層物件 ─────────────────────────────────────
        CliController cliController =
                new CliController(discoverPeersUseCase, sendFileUseCase);

        // ── Step 4: 建立 CLI 外層，執行指令 ──────────────────────────────────
        PicocliRunner runner = new PicocliRunner(cliController);
        int exitCode = runner.run(args);

        // System.exit 集中在此，讓 PicocliRunner 可被單元測試
        System.exit(exitCode);
    }

    /**
     * 取得本機 hostname 作為 P2P 節點名稱。
     * 若無法解析則回傳預設名稱。
     *
     * @return 本機 hostname，例如 "Chang-MacBook-Pro"
     */
    private static String resolveLocalHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "AirDrop-Node";
        }
    }

    /**
     * 建立接收端的 FileTransferListener，用於在 TCP Server 收到檔案時顯示進度。
     *
     * <p>
     * 注意：這個 Listener 在 Netty IO thread 中執行，
     * 不應在此做耗時操作。
     * </p>
     *
     * @return 接收端進度監聽器
     */
    private static FileTransferListener buildReceiveListener() {
        return new FileTransferListener() {
            @Override
            public void onProgressUpdated(FileTask task) {
                if (task == null) return;
                if (task.getStatus() == FileTask.Status.COMPLETED) {
                    System.out.printf("%n[✓] 已接收完成：%s%n", task.getFilePath());
                } else {
                    int percent = (int) (task.getProgress() * 100);
                    System.out.printf("\r[接收中] %s  %d%%", task.getFileName(), percent);
                }
            }

            @Override
            public void onError(FileTask task, String errorMessage) {
                System.err.printf("%n[✗] 接收失敗：%s%n", errorMessage);
            }
        };
    }
}
