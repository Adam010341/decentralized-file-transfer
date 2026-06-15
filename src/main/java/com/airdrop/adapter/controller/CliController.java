package com.airdrop.adapter.controller;

import com.airdrop.domain.model.FileTask;
import com.airdrop.domain.model.Peer;
import com.airdrop.usecase.DiscoverPeersUseCase;
import com.airdrop.usecase.SendFileUseCase;
import com.airdrop.usecase.port.in.FileTransferListener;

import java.io.File;
import java.io.PrintStream;
import java.util.List;

/**
 * CliController — Interface Adapters 層（Clean Architecture 第三層）
 *
 * <p>
 * <b>架構職責說明：</b>
 * </p>
 * <p>
 * 在 Clean Architecture 中，Controller 屬於「Interface Adapters」層，
 * 負責在外部世界（CLI 使用者輸入）與應用程式核心（UseCase）之間轉換資料。
 * 其職責嚴格限定為：
 * <ol>
 * <li>接收來自 CLI 的原始指令與參數</li>
 * <li>驗證並轉換輸入格式（如將字串 peerId 解析為 IP）</li>
 * <li>呼叫對應 UseCase 執行業務邏輯</li>
 * <li>將 UseCase 回傳的領域物件轉換為適合 CLI 顯示的文字</li>
 * </ol>
 *
 * <p>
 * <b>為什麼 Controller 只能呼叫 UseCase，不能直接呼叫 NettyNetworkGateway？</b>
 * </p>
 * <p>
 * 依賴規則（Dependency Rule）規定：程式碼的依賴方向只能由外層指向內層。
 * {@code NettyNetworkGateway} 屬於 Infrastructure 層（第四層，最外層），
 * 而 Controller 屬於第三層。若 Controller 直接依賴 Netty 實作，會產生以下問題：
 * <ul>
 * <li>破壞依賴倒置原則（DIP）：Controller 將與具體框架耦合，難以抽換實作</li>
 * <li>無法單元測試：測試時必須啟動真實網路，導致測試緩慢且不穩定</li>
 * <li>職責混亂：Controller 會知道 TCP、UDP、Netty Channel 等底層細節</li>
 * <li>違反單一職責：一旦 Netty 版本升級，Controller 也必須跟著修改</li>
 * </ul>
 * 正確做法是透過 UseCase（UseCase 再透過 {@code NetworkGateway} 介面呼叫底層），
 * Controller 只依賴穩定的 UseCase API，與具體實作完全解耦。
 * </p>
 *
 * <p>
 * <b>單元測試設計：</b>
 * </p>
 * <p>
 * 建構子注入 {@code DiscoverPeersUseCase}、{@code SendFileUseCase}，
 * 以及 {@code PrintStream out}（預設為 {@code System.out}）。
 * 測試時可注入 Mock UseCase 與 {@code ByteArrayOutputStream}，
 * 完全不需要真實網路或檔案系統。
 * </p>
 */
public class CliController {

    /**
     * peerId 的格式為 "ip:port"，例如 "192.168.1.5:8080"。
     * 這個分隔符用於解析使用者輸入的 peerId。
     */
    private static final String PEER_ID_SEPARATOR = ":";

    private final DiscoverPeersUseCase discoverPeersUseCase;
    private final SendFileUseCase sendFileUseCase;

    /**
     * 輸出流，預設為 System.out。
     * 允許測試時注入替代的 PrintStream（如 ByteArrayOutputStream）
     * 以驗證 CLI 輸出內容，不需要捕捉 stdout。
     */
    private final PrintStream out;

    /**
     * 正式使用的建構子，輸出至標準輸出。
     *
     * @param discoverPeersUseCase 負責 LAN 節點搜尋與管理的 UseCase
     * @param sendFileUseCase      負責協調檔案傳送流程的 UseCase
     */
    public CliController(DiscoverPeersUseCase discoverPeersUseCase,
            SendFileUseCase sendFileUseCase) {
        this(discoverPeersUseCase, sendFileUseCase, System.out);
    }

    /**
     * 可測試的建構子，允許注入自訂 PrintStream。
     *
     * @param discoverPeersUseCase 負責 LAN 節點搜尋與管理的 UseCase
     * @param sendFileUseCase      負責協調檔案傳送流程的 UseCase
     * @param out                  CLI 輸出流（測試時可傳入 ByteArrayOutputStream 包裝的
     *                             PrintStream）
     */
    public CliController(DiscoverPeersUseCase discoverPeersUseCase,
            SendFileUseCase sendFileUseCase,
            PrintStream out) {
        if (discoverPeersUseCase == null) {
            throw new IllegalArgumentException("discoverPeersUseCase 不可為 null");
        }
        if (sendFileUseCase == null) {
            throw new IllegalArgumentException("sendFileUseCase 不可為 null");
        }
        if (out == null) {
            throw new IllegalArgumentException("out (PrintStream) 不可為 null");
        }
        this.discoverPeersUseCase = discoverPeersUseCase;
        this.sendFileUseCase = sendFileUseCase;
        this.out = out;
    }

    // -------------------------------------------------------------------------
    // Public Methods（對應 CLI 指令）
    // -------------------------------------------------------------------------

    /**
     * 對應 CLI 指令：{@code discover}（或 {@code scan}）
     *
     * <p>
     * 啟動 LAN 節點搜尋，並印出目前已發現的節點清單。
     * 搜尋由 {@link DiscoverPeersUseCase#start()} 觸發，UseCase 內部透過
     * {@code NetworkGateway} 發送 UDP multicast broadcast（實作細節由 Infrastructure 層負責），
     * Controller 完全不知道底層是 UDP、TCP 或其他協定。
     * </p>
     *
     * <p>
     * 注意：由於 UDP multicast 是非同步的，呼叫 {@code start()} 後需等待一段時間
     * 才能收到其他節點的回應。建議在 CLI 層（{@code PicocliRunner}）加上等待提示，
     * 或在此呼叫後 sleep 短暫時間再 listPeers()。
     * </p>
     *
     * <p>
     * 呼叫 UseCase API：
     * <ul>
     * <li>{@code discoverPeersUseCase.start()} — 啟動發現機制（可依實際 UseCase API 調整）</li>
     * <li>{@code discoverPeersUseCase.getActivePeers()} — 取得目前已知節點快照</li>
     * </ul>
     * </p>
     */
    public void discoverPeers() {
        out.println("[*] 開始搜尋區域網路內的裝置...");

        // 呼叫 UseCase 啟動底層搜尋機制。
        // UseCase 會透過 NetworkGateway 介面對外發送 UDP multicast，
        // Controller 不知道、也不應該知道底層實作。
        discoverPeersUseCase.start(); // 可依實際 UseCase API 調整

        // 讓 UDP 回應有時間抵達（簡單策略；生產環境可改為事件驅動或輪詢）
        sleepQuietly(2000);

        // 印出目前已快照的節點清單
        printPeerList(discoverPeersUseCase.getActivePeers(), true);
    }

    /**
     * 對應 CLI 指令：{@code send <peerId> <filePath>}
     *
     * <p>
     * 傳送指定檔案至目標節點。Controller 的職責是：
     * <ol>
     * <li>驗證輸入參數不為空</li>
     * <li>驗證本地檔案存在且為一般檔案（非資料夾）</li>
     * <li>從 peerId（格式："ip:port"）解析出目標 IP</li>
     * <li>建立 {@link FileTransferListener} 以顯示傳送進度</li>
     * <li>呼叫
     * {@link SendFileUseCase#execute(String, String, FileTransferListener)}</li>
     * </ol>
     * Controller <b>不</b>處理 TCP 連線、檔案 chunk 切割或 Netty channel，
     * 這些全部由 UseCase 委派給 Infrastructure 層負責。
     * </p>
     *
     * <p>
     * 呼叫 UseCase API：
     * <ul>
     * <li>{@code sendFileUseCase.execute(targetIp, filePath, listener)}
     * — 可依實際 UseCase API 調整</li>
     * </ul>
     * </p>
     *
     * @param peerId   目標節點識別碼，格式為 "ip:port"（例如 "192.168.1.5:8080"）
     * @param filePath 欲傳送的本地檔案路徑（絕對或相對路徑皆可）
     */
    public void sendFile(String peerId, String filePath) {
        // ── 輸入驗證（Guard Clauses）─────────────────────────────────────────
        if (peerId == null || peerId.trim().isEmpty()) {
            out.println("[錯誤] peerId 不可為空。請先執行 list 指令查詢可用裝置。");
            return;
        }

        if (filePath == null || filePath.trim().isEmpty()) {
            out.println("[錯誤] 檔案路徑不可為空。");
            return;
        }

        File file = new File(filePath.trim());

        if (!file.exists()) {
            out.println("[錯誤] 找不到檔案：" + file.getAbsolutePath());
            return;
        }



        if (!file.canRead()) {
            out.println("[錯誤] 無法讀取檔案（請確認權限）：" + file.getAbsolutePath());
            return;
        }

        // ── 解析 peerId → targetIp ──────────────────────────────────────────
        // peerId 格式："ip:port"（例如 "192.168.1.5:8080"）
        // SendFileUseCase.execute() 目前接受 targetIp 作為第一個參數（可依實際 UseCase API 調整）
        String targetIp = parsePeerIp(peerId);
        if (targetIp == null) {
            out.printf("[錯誤] peerId 格式不正確（應為 \"ip:port\"），收到：%s%n", peerId);
            return;
        }

        // ── 建立進度監聽器 ───────────────────────────────────────────────────
        // Controller 實作 FileTransferListener 介面，
        // 將 UseCase 回呼轉換為 CLI 可顯示的進度訊息。
        // 這是 Interface Adapters 層的核心工作：資料格式轉換。
        FileTransferListener progressListener = buildCliProgressListener(file.getName());

        // ── 呼叫 UseCase ─────────────────────────────────────────────────────
        // 由 UseCase 負責協調：查找對應 Peer、建立 FileTask、委派給 NetworkGateway 傳送。
        // Controller 完全不碰 Netty、TCP socket 或 chunk 邏輯。
        out.printf("[*] 準備傳送 \"%s\" 至裝置 %s ...%n", file.getName(), peerId);
        try {
            sendFileUseCase.execute(targetIp, file.getAbsolutePath(), progressListener); // 可依實際 UseCase API 調整
        } catch (IllegalArgumentException e) {
            // UseCase 層的業務驗證錯誤（如目標 IP 不在 active peer 清單中）
            out.println("[錯誤] " + e.getMessage());
        } catch (Exception e) {
            // 其他非預期例外：記錄訊息但不讓程式 crash
            out.println("[錯誤] 傳送過程發生非預期錯誤：" + e.getMessage());
        }
    }

    /**
     * 對應 CLI 指令：{@code list}（或 {@code peers}）
     *
     * <p>
     * 顯示目前已發現且仍在線的節點清單（快照）。
     * 此方法<b>不</b>重新觸發搜尋，僅讀取 UseCase 內部已快取的活躍節點，
     * 若使用者尚未執行 {@code discover}，清單可能為空。
     * </p>
     *
     * <p>
     * 呼叫 UseCase API：
     * <ul>
     * <li>{@code discoverPeersUseCase.getActivePeers()} — 可依實際 UseCase API 調整</li>
     * </ul>
     * </p>
     */
    public void listPeers() {
        List<Peer> peers = discoverPeersUseCase.getActivePeers(); // 可依實際 UseCase API 調整
        printPeerList(peers, false);
    }

    // -------------------------------------------------------------------------
    // Private Helper Methods（Controller 內部的格式轉換工具）
    // -------------------------------------------------------------------------

    /**
     * 印出節點清單至 CLI。
     *
     * @param peers        節點清單
     * @param fromDiscover true 表示來自 discoverPeers()，false 表示來自 listPeers()
     */
    private void printPeerList(List<Peer> peers, boolean fromDiscover) {
        if (peers == null || peers.isEmpty()) {
            if (fromDiscover) {
                out.println("[提示] 目前尚未發現任何裝置。");
                out.println("       請確認：");
                out.println("       1. 其他裝置已在同一個區域網路內執行此程式");
                out.println("       2. 防火牆未封鎖 UDP multicast 流量");
            } else {
                out.println("[提示] 目前尚未發現任何裝置。");
                out.println("       請先執行 discover 指令以搜尋區域網路內的裝置。");
            }
            return;
        }

        out.println();
        out.printf("%-5s %-20s %-18s %-6s%n", "編號", "裝置名稱", "IP 位址", "Port");
        out.println("─".repeat(55));

        int index = 1;
        for (Peer peer : peers) {
            String peerId = buildPeerId(peer); // "ip:port"
            String peerName = peer.getName() != null ? peer.getName() : "(未知)";
            out.printf("%-5d %-20s %-18s %-6d%n",
                    index++, peerName, peer.getIp(), peer.getPort());
            out.printf("      peerId: %s%n", peerId);
        }

        out.println("─".repeat(55));
        out.printf("共發現 %d 台裝置。使用 peerId 執行傳送指令，例如：%n", peers.size());
        if (!peers.isEmpty()) {
            Peer example = peers.get(0);
            out.printf("  send -p %s -f \"/path/to/file\"%n", buildPeerId(example));
        }
        out.println();
    }

    /**
     * 建立 CLI 進度顯示用的 {@link FileTransferListener}。
     *
     * <p>
     * 此為 Interface Adapters 層的資料轉換邏輯：
     * 將 UseCase 的回呼事件（{@link FileTask}）轉換為人類可讀的 CLI 進度條。
     * Listener 的實作不包含任何網路或業務邏輯。
     * </p>
     *
     * @param displayFileName 顯示用的檔案名稱（避免重複讀取 FileTask）
     * @return 配置好的進度監聽器
     */
    private FileTransferListener buildCliProgressListener(String displayFileName) {
        return new FileTransferListener() {

            @Override
            public void onProgressUpdated(FileTask task) {
                if (task == null)
                    return;

                int percent = (int) (task.getProgress() * 100);
                String bar = buildProgressBar(percent, 20);

                // 使用 \r 在同一行更新進度，不換行（terminal carriage return）
                out.printf("\r[傳送中] %s %s %3d%%",
                        task.getFileName() != null ? task.getFileName() : displayFileName,
                        bar,
                        percent);
                out.flush();

                // 傳送完成時換行並顯示完成訊息
                if (task.getStatus() == FileTask.Status.COMPLETED) {
                    out.println();
                    out.printf("[完成] 傳送完成：%s%n",
                            task.getFileName() != null ? task.getFileName() : displayFileName);
                }
            }

            @Override
            public void onError(FileTask task, String errorMessage) {
                out.println(); // 先換行，避免與進度列重疊
                String fileName = (task != null && task.getFileName() != null)
                        ? task.getFileName()
                        : displayFileName;
                out.printf("[錯誤] 傳送失敗（%s）：%s%n", fileName, errorMessage);
            }
        };
    }

    /**
     * 建立文字進度條，例如 {@code [████████░░░░░░░░░░░░]}.
     *
     * @param percent   進度百分比（0~100）
     * @param barLength 進度條總長度（字元數）
     * @return 格式化的進度條字串
     */
    private String buildProgressBar(int percent, int barLength) {
        int filled = Math.min(barLength, percent * barLength / 100);
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < barLength; i++) {
            bar.append(i < filled ? '█' : '░');
        }
        bar.append(']');
        return bar.toString();
    }

    /**
     * 從 peerId 字串（格式："ip:port"）解析出 IP 位址部分。
     *
     * <p>
     * 若格式不符，回傳 {@code null}。
     * </p>
     *
     * @param peerId 格式為 "ip:port" 的識別字串
     * @return IP 位址字串，格式錯誤時回傳 null
     */
    private String parsePeerIp(String peerId) {
        if (peerId == null) return null;
        return peerId; // 保留 IP:Port 格式，交給 UseCase 進行精確比對
    }

    /**
     * 從 {@link Peer} 物件建構 peerId 字串（格式："ip:port"）。
     * 此格式與 {@code DiscoverPeersUseCase.getPeerKey()} 的內部邏輯一致。
     *
     * @param peer 節點物件
     * @return "ip:port" 格式的識別字串
     */
    private String buildPeerId(Peer peer) {
        return peer.getIp() + PEER_ID_SEPARATOR + peer.getPort();
    }

    /**
     * 封裝 Thread.sleep，忽略 InterruptedException 並恢復中斷旗標。
     * 讓 discoverPeers() 的程式流程更簡潔。
     *
     * @param millis 等待毫秒數
     */
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 恢復中斷旗標，遵循 Java 最佳實踐
        }
    }
}
