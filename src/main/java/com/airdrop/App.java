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

    // 儲存所有接收端的進度監聽器 (包含 CLI 與 GUI)
    private static final java.util.List<FileTransferListener> receiveListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void main(String[] args) {

        // ── Step 1: 建立 Infrastructure 層物件 ──────────────────────────────
        NettyNetworkGateway gateway = new NettyNetworkGateway();
        String localName = resolveLocalHostname();
        gateway.setLocalPeerName(localName);

        // TCP Server：監聽接收端傳入的檔案（port 0 = 作業系統隨機分配）
        try {
            // 設定接收檔案時的進度 listener（印至 stdout 及更新 GUI）
            receiveListeners.add(buildReceiveListener()); // 加入預設的 CLI 輸出與解壓縮邏輯
            gateway.setFileTransferListener(new FileTransferListener() {
                @Override
                public void onProgressUpdated(FileTask task) {
                    for (FileTransferListener l : receiveListeners) {
                        l.onProgressUpdated(task);
                    }
                }
                @Override
                public void onError(FileTask task, String errorMessage) {
                    for (FileTransferListener l : receiveListeners) {
                        l.onError(task, errorMessage);
                    }
                }
            });
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

        // 啟動 GUI 介面 (與 CLI 同時執行)
        javax.swing.SwingUtilities.invokeLater(() -> {
            try {
                javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                // Ignore and use default look and feel
            }
            com.airdrop.adapter.controller.gui.AirDropGui gui = 
                new com.airdrop.adapter.controller.gui.AirDropGui(discoverPeersUseCase, sendFileUseCase);
            
            // 將 GUI 的接收進度監聽器註冊到背景任務中
            receiveListeners.add(gui.getReceivingListener());
            
            gui.setVisible(true);
        });

        // ── Step 4: 建立 CLI 外層，執行指令 ──────────────────────────────────
        PicocliRunner runner = new PicocliRunner(cliController);
        
        if (args != null && args.length > 0) {
            // 單次執行模式：執行指令後結束
            int exitCode = runner.run(args);
            System.exit(exitCode);
        } else {
            // 互動式常駐模式 (REPL)：保持執行，等待並處理指令
            System.out.println("\n=================================================");
            System.out.println("🚀 歡迎使用 AirDrop P2P 檔案傳輸");
            System.out.println("✅ 程式已進入「常駐監聽模式」，此時別人可以發現你並傳送檔案。");
            System.out.println("👉 請直接輸入指令 (例如: discover, list, send -p IP:Port -f 檔案)");
            System.out.println("👉 輸入 exit 或 quit 離開");
            System.out.println("=================================================");
            
            java.io.Console console = System.console();
            java.util.Scanner scanner = (console == null) ? new java.util.Scanner(System.in) : null;

            while (true) {
                String line;
                if (console != null) {
                    line = console.readLine("\nairdrop> ");
                    if (line == null) break;
                } else {
                    System.out.print("\nairdrop> ");
                    if (!scanner.hasNextLine()) break;
                    line = scanner.nextLine();
                }
                
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                    break;
                }
                
                // 簡易參數解析，支援雙引號包覆的檔案路徑
                java.util.List<String> cmdArgsList = new java.util.ArrayList<>();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("([^\"]\\S*|\".+?\")\\s*").matcher(line);
                while (m.find()) {
                    cmdArgsList.add(m.group(1).replace("\"", ""));
                }
                
                runner.run(cmdArgsList.toArray(new String[0]));
            }
            System.exit(0);
        }
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
                    if (task.isDirectory()) {
                        System.out.printf("%n[✓] 已接收資料夾壓縮檔：%s%n", task.getFilePath());
                        System.out.printf("[*] 正在背景自動解壓縮資料夾...%n");
                        
                        // 啟動背景執行緒進行解壓縮，避免阻塞 Netty IO Thread
                        new Thread(() -> {
                            try {
                                java.io.File zipFile = new java.io.File(task.getFilePath());
                                // 移除 .zip 副檔名作為目標資料夾名稱
                                String targetDirName = task.getFileName();
                                if (targetDirName.toLowerCase().endsWith(".zip")) {
                                    targetDirName = targetDirName.substring(0, targetDirName.length() - 4);
                                }
                                java.io.File destDir = new java.io.File("./downloaded_" + targetDirName);
                                
                                com.airdrop.infrastructure.util.ZipUtil.unzip(zipFile, destDir);
                                zipFile.delete(); // 解壓縮成功後刪除暫存的 zip 檔
                                
                                System.out.printf("%n[✓] 資料夾解壓縮完成，已儲存至：%s%nairdrop> ", destDir.getAbsolutePath());
                            } catch (Exception e) {
                                System.err.printf("%n[✗] 資料夾解壓縮失敗：%s%nairdrop> ", e.getMessage());
                            }
                        }, "unzip-thread").start();
                        
                    } else {
                        System.out.printf("%n[✓] 已接收完成：%s%n", task.getFilePath());
                    }
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
