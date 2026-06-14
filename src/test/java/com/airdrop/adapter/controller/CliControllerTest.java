package com.airdrop.adapter.controller;

import com.airdrop.domain.model.FileTask;
import com.airdrop.domain.model.Peer;
import com.airdrop.usecase.DiscoverPeersUseCase;
import com.airdrop.usecase.SendFileUseCase;
import com.airdrop.usecase.port.in.FileTransferListener;
import com.airdrop.usecase.port.in.PeerDiscoveryListener;
import com.airdrop.usecase.port.out.NetworkGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CliController 單元測試
 *
 * <p><b>測試策略：手工測試替身（Manual Test Doubles）</b></p>
 * <p>
 * {@code DiscoverPeersUseCase} 和 {@code SendFileUseCase} 都是具體類別，
 * 在 Java 21+ 環境下，Mockito 5.x 的 inline mock 無法對 JDK 類別進行 byte-buddy
 * instrumentation（需要 javaagent），因此本測試改用手工替身：
 * <ul>
 *   <li>{@link DiscoverPeersSpy} — 繼承 DiscoverPeersUseCase，覆寫所有方法，
 *       無需真實網路或排程器，可設定回傳資料並追蹤呼叫次數。</li>
 *   <li>{@link SendFileSpy} — 繼承 SendFileUseCase，覆寫 execute()，
 *       可記錄入參、設定拋出例外、主動觸發 listener 回呼。</li>
 * </ul>
 * 此方式不依賴任何 mock 框架，可在任何 JVM 版本上穩定執行。
 * </p>
 *
 * <p><b>CLI 輸出驗證：</b>注入 ByteArrayOutputStream 包裝的 PrintStream，
 * 攔截所有 System.out 輸出，不需捕捉 stdout。</p>
 *
 * <p><b>注意：</b>{@code discoverPeers()} 內部有 2 秒 sleep，
 * 因此測試該方法的案例各需約 2 秒執行時間。</p>
 */
class CliControllerTest {

    // =========================================================================
    // 手工測試替身（Test Doubles）
    // =========================================================================

    /**
     * 所有網路操作皆空實作的 NetworkGateway，
     * 用於替身類別的 super() 呼叫，不需要真實網路。
     */
    private static final NetworkGateway NO_OP_GATEWAY = new NetworkGateway() {
        @Override public void startDiscovery(PeerDiscoveryListener l) {}
        @Override public void stopDiscovery() {}
        @Override public void sendFile(Peer p, FileTask ft, FileTransferListener l) {}
    };

    /**
     * DiscoverPeersUseCase 的可觀察替身（Spy）。
     *
     * <p>覆寫 start() / stop() / getActivePeers()，
     * 使父類別的網路排程邏輯完全不執行。
     * 透過 {@code startCallCount} 和 {@code activePeers} 控制測試行為。</p>
     */
    static class DiscoverPeersSpy extends DiscoverPeersUseCase {

        /** 記錄 start() 被呼叫的次數 */
        int startCallCount = 0;

        /** 測試可設定 getActivePeers() 回傳的節點清單 */
        List<Peer> activePeers = new ArrayList<>();

        /**
         * 使用 daemon thread 的排程器，避免 JVM 在測試結束後因非 daemon thread 卡住。
         * 由於 start() 已被覆寫，此排程器實際上不會被使用。
         */
        private static final ScheduledExecutorService DUMMY_SCHEDULER =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "test-dummy-scheduler");
                    t.setDaemon(true);
                    return t;
                });

        DiscoverPeersSpy() {
            super(NO_OP_GATEWAY, DUMMY_SCHEDULER, Clock.systemUTC());
        }

        /** 覆寫：只計數，不觸發 UDP multicast 或排程 */
        @Override public void start() { startCallCount++; }

        /** 覆寫：空實作 */
        @Override public void stop() {}

        /** 覆寫：回傳測試設定的節點清單，不查詢網路 */
        @Override public List<Peer> getActivePeers() { return new ArrayList<>(activePeers); }
    }

    /**
     * SendFileUseCase 的可觀察替身（Spy）。
     *
     * <p>覆寫 execute()，捕捉所有入參供斷言使用，
     * 並可設定拋出例外或主動觸發 FileTransferListener 回呼。</p>
     */
    static class SendFileSpy extends SendFileUseCase {

        /** 最後一次呼叫 execute() 時傳入的 targetIp，未呼叫時為 null */
        String lastIp;

        /** 最後一次呼叫 execute() 時傳入的 filePath，未呼叫時為 null */
        String lastPath;

        /** 最後一次呼叫 execute() 時傳入的 listener，未呼叫時為 null */
        FileTransferListener lastListener;

        /** 若設定此值，execute() 會拋出此例外 */
        RuntimeException toThrow;

        /** 若設定此值，execute() 會在儲存參數後呼叫此 Runnable，用於觸發 listener 回呼 */
        Runnable onExecute;

        SendFileSpy(DiscoverPeersSpy discoverSpy) {
            super(NO_OP_GATEWAY, discoverSpy);
        }

        @Override
        public void execute(String targetIp, String filePath, FileTransferListener listener) {
            lastIp = targetIp;
            lastPath = filePath;
            lastListener = listener;
            if (toThrow != null) throw toThrow;
            if (onExecute != null) onExecute.run();
        }
    }

    // =========================================================================
    // 測試設定
    // =========================================================================

    private DiscoverPeersSpy discoverSpy;
    private SendFileSpy sendSpy;
    private ByteArrayOutputStream outBuffer;
    private CliController controller;

    @BeforeEach
    void setUp() {
        discoverSpy = new DiscoverPeersSpy();
        sendSpy = new SendFileSpy(discoverSpy);
        outBuffer = new ByteArrayOutputStream();
        controller = new CliController(discoverSpy, sendSpy, new PrintStream(outBuffer));
    }

    /** 取得目前 CLI 輸出內容 */
    private String output() {
        return outBuffer.toString();
    }

    // =========================================================================
    // 建構子驗證
    // =========================================================================

    @Nested
    @DisplayName("建構子驗證")
    class ConstructorTests {

        @Test
        @DisplayName("null discoverPeersUseCase 應拋 IllegalArgumentException")
        void nullDiscover_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> new CliController(null, sendSpy));
        }

        @Test
        @DisplayName("null sendFileUseCase 應拋 IllegalArgumentException")
        void nullSend_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> new CliController(discoverSpy, null));
        }

        @Test
        @DisplayName("null PrintStream 應拋 IllegalArgumentException")
        void nullOut_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> new CliController(discoverSpy, sendSpy, null));
        }

        @Test
        @DisplayName("合法參數應成功建立 Controller 實例")
        void validArgs_createsInstance() {
            assertDoesNotThrow(() -> new CliController(discoverSpy, sendSpy));
        }
    }

    // =========================================================================
    // discoverPeers() 測試群（每個測試約 +2 秒，因 Controller 內有 sleep）
    // =========================================================================

    @Nested
    @DisplayName("discoverPeers()")
    class DiscoverPeersTests {

        @Test
        @DisplayName("應呼叫 discoverPeersUseCase.start() 一次")
        void callsStart_once() {
            controller.discoverPeers();
            assertEquals(1, discoverSpy.startCallCount,
                    "discoverPeers() 應呼叫 start() 恰好一次");
        }

        @Test
        @DisplayName("無 peer 時應顯示空裝置提示")
        void noPeers_showsEmptyHint() {
            controller.discoverPeers();
            assertTrue(output().contains("尚未發現任何裝置"),
                    "無 peer 時應顯示尚未發現任何裝置。實際輸出：\n" + output());
        }

        @Test
        @DisplayName("有 peer 時應顯示裝置名稱、IP、Port")
        void withPeer_showsInfo() {
            discoverSpy.activePeers.add(new Peer("Alice-Mac", "192.168.1.10", 8080));
            controller.discoverPeers();
            String o = output();
            assertTrue(o.contains("Alice-Mac"),    "應顯示裝置名稱");
            assertTrue(o.contains("192.168.1.10"), "應顯示 IP");
            assertTrue(o.contains("8080"),         "應顯示 Port");
        }

        @Test
        @DisplayName("多個 peer 全部顯示並印出數量統計")
        void multiplePeers_showsAllAndCount() {
            discoverSpy.activePeers.add(new Peer("DevA", "10.0.0.1", 8080));
            discoverSpy.activePeers.add(new Peer("DevB", "10.0.0.2", 8080));
            discoverSpy.activePeers.add(new Peer("DevC", "10.0.0.3", 9090));
            controller.discoverPeers();
            String o = output();
            assertTrue(o.contains("DevA") && o.contains("DevB") && o.contains("DevC"),
                    "三台裝置都應顯示");
            assertTrue(o.contains("3 台裝置"), "應顯示發現數量");
        }

        @Test
        @DisplayName("peer.getName() 為 null 時應以 (未知) 顯示")
        void nullPeerName_showsUnknown() {
            discoverSpy.activePeers.add(new Peer(null, "10.0.0.1", 7777));
            controller.discoverPeers();
            assertTrue(output().contains("(未知)"), "null name 應以 (未知) 取代");
        }
    }

    // =========================================================================
    // listPeers() 測試群
    // =========================================================================

    @Nested
    @DisplayName("listPeers()")
    class ListPeersTests {

        @Test
        @DisplayName("無 peer 時應提示使用者先執行 discover 指令")
        void noPeers_suggestsDiscover() {
            controller.listPeers();
            assertTrue(output().contains("discover"),
                    "應提示使用者執行 discover 指令");
        }

        @Test
        @DisplayName("有 peer 時應顯示 ip:port 格式的 peerId")
        void withPeer_showsPeerId() {
            discoverSpy.activePeers.add(new Peer("Bob-PC", "192.168.0.5", 9000));
            controller.listPeers();
            assertTrue(output().contains("192.168.0.5:9000"),
                    "應顯示 ip:port 格式的 peerId");
        }

        @Test
        @DisplayName("listPeers() 不應呼叫 discoverPeersUseCase.start()")
        void doesNotTriggerDiscovery() {
            controller.listPeers();
            assertEquals(0, discoverSpy.startCallCount,
                    "listPeers 不應觸發 start()，只讀取快取");
        }

        @Test
        @DisplayName("listPeers() 不應呼叫 sendFileUseCase.execute()")
        void doesNotCallSendUseCase() {
            controller.listPeers();
            assertNull(sendSpy.lastIp,
                    "listPeers 不應觸發 sendFileUseCase.execute()");
        }
    }

    // =========================================================================
    // sendFile() — 輸入驗證測試群
    // =========================================================================

    @Nested
    @DisplayName("sendFile() — 輸入驗證")
    class SendFileValidationTests {

        @Test
        @DisplayName("null peerId 應顯示錯誤且不呼叫 UseCase")
        void nullPeerId_showsError() {
            controller.sendFile(null, "/some/file.txt");
            assertTrue(output().contains("[錯誤]"), "應顯示 [錯誤] 標籤");
            assertNull(sendSpy.lastIp, "不應呼叫 UseCase");
        }

        @Test
        @DisplayName("空白 peerId 應顯示錯誤且不呼叫 UseCase")
        void blankPeerId_showsError() {
            controller.sendFile("   ", "/some/file.txt");
            assertTrue(output().contains("[錯誤]"));
            assertNull(sendSpy.lastIp);
        }

        @Test
        @DisplayName("null filePath 應顯示錯誤且不呼叫 UseCase")
        void nullFilePath_showsError() {
            controller.sendFile("192.168.1.1:8080", null);
            assertTrue(output().contains("[錯誤]"));
            assertNull(sendSpy.lastIp);
        }

        @Test
        @DisplayName("空白 filePath 應顯示錯誤且不呼叫 UseCase")
        void blankFilePath_showsError() {
            controller.sendFile("192.168.1.1:8080", "");
            assertTrue(output().contains("[錯誤]"));
            assertNull(sendSpy.lastIp);
        }

        @Test
        @DisplayName("不存在的檔案路徑應顯示「找不到檔案」")
        void nonExistentFile_showsNotFoundError() {
            controller.sendFile("192.168.1.1:8080", "/totally/fake/path/no.zip");
            assertTrue(output().contains("[錯誤]"));
            assertTrue(output().contains("找不到檔案"),
                    "應明確說明找不到檔案。實際輸出：\n" + output());
            assertNull(sendSpy.lastIp);
        }

        @Test
        @DisplayName("指向資料夾應該順利呼叫 UseCase 進行傳送")
        void directory_callsUseCase(@TempDir Path tempDir) {
            controller.sendFile("192.168.1.1:8080", tempDir.toString());
            assertEquals("192.168.1.1", sendSpy.lastIp,
                    "指向資料夾應該成功解析 IP 並呼叫 UseCase");
        }

        @Test
        @DisplayName("peerId 格式無冒號應顯示格式錯誤")
        void invalidPeerIdNoColon_showsFormatError(@TempDir Path tempDir) throws IOException {
            // 使用真實檔案讓錯誤在 peerId 解析階段觸發
            Path file = Files.createFile(tempDir.resolve("test.txt"));
            Files.writeString(file, "content");
            controller.sendFile("no-colon-here", file.toString());
            assertTrue(output().contains("[錯誤]"));
            assertTrue(output().contains("格式"),
                    "應說明 peerId 格式問題。實際輸出：\n" + output());
            assertNull(sendSpy.lastIp);
        }
    }

    // =========================================================================
    // sendFile() — 成功路徑測試群
    // =========================================================================

    @Nested
    @DisplayName("sendFile() — 成功路徑")
    class SendFileSuccessTests {

        @Test
        @DisplayName("應從 peerId 解析出正確的 targetIp（不含 port）")
        void parsesIpFromPeerId(@TempDir Path tempDir) throws IOException {
            Path file = Files.createFile(tempDir.resolve("report.pdf"));
            Files.writeString(file, "content");

            controller.sendFile("192.168.1.5:8080", file.toString());

            assertEquals("192.168.1.5", sendSpy.lastIp,
                    "應從 '192.168.1.5:8080' 解析出 IP '192.168.1.5'");
        }

        @Test
        @DisplayName("複雜 IPv4（含多個 '.'）應正確解析（lastIndexOf 策略）")
        void complexIpParsing(@TempDir Path tempDir) throws IOException {
            Path file = Files.createFile(tempDir.resolve("img.png"));
            Files.writeString(file, "data");

            controller.sendFile("10.100.200.50:12345", file.toString());

            assertEquals("10.100.200.50", sendSpy.lastIp,
                    "lastIndexOf(':') 應正確處理多個 '.' 的 IPv4");
        }

        @Test
        @DisplayName("傳給 UseCase 的 filePath 應為絕對路徑")
        void passesAbsolutePath(@TempDir Path tempDir) throws IOException {
            Path file = Files.createFile(tempDir.resolve("data.csv"));
            Files.writeString(file, "col1");

            controller.sendFile("10.0.0.1:9000", file.toString());

            assertTrue(new File(sendSpy.lastPath).isAbsolute(),
                    "傳給 UseCase 的路徑應為絕對路徑，實際：" + sendSpy.lastPath);
        }

        @Test
        @DisplayName("傳送前應顯示包含檔名與 peerId 的準備訊息")
        void showsPreparingMessage(@TempDir Path tempDir) throws IOException {
            Path file = Files.createFile(tempDir.resolve("video.mp4"));
            Files.writeString(file, "fake");

            controller.sendFile("192.168.1.1:8080", file.toString());

            String o = output();
            assertTrue(o.contains("video.mp4"),        "應顯示檔名");
            assertTrue(o.contains("192.168.1.1:8080"), "應顯示目標 peerId");
        }

        @Test
        @DisplayName("FileTransferListener 應以非 null 值傳給 UseCase")
        void passesNonNullListener(@TempDir Path tempDir) throws IOException {
            Path file = Files.createFile(tempDir.resolve("x.bin"));
            Files.writeString(file, "x");

            controller.sendFile("1.2.3.4:8080", file.toString());

            assertNotNull(sendSpy.lastListener,
                    "Controller 應傳遞 listener 給 UseCase");
        }
    }

    // =========================================================================
    // sendFile() — FileTransferListener 回呼行為測試群
    // =========================================================================

    @Nested
    @DisplayName("sendFile() — Listener 回呼行為")
    class ListenerCallbackTests {

        @Test
        @DisplayName("onProgressUpdated 觸發時輸出應包含檔名與百分比數字")
        void progressUpdated_showsFileNameAndPercent(@TempDir Path tempDir) throws IOException {
            Path file = Files.createFile(tempDir.resolve("archive.zip"));
            Files.writeString(file, "zip content here");

            // 建立 50% 進度的 FileTask
            FileTask task = new FileTask("t1", file.toString(), "archive.zip", 1000L);
            task.setBytesTransferred(500L);
            task.setStatus(FileTask.Status.RUNNING);

            // 設定 spy：execute() 被呼叫時立即觸發進度回呼
            sendSpy.onExecute = () -> sendSpy.lastListener.onProgressUpdated(task);

            controller.sendFile("192.168.1.1:8080", file.toString());

            String o = output();
            assertTrue(o.contains("archive.zip"), "應顯示正在傳送的檔名");
            assertTrue(o.contains("50"),          "應顯示 50% 進度數字");
        }

        @Test
        @DisplayName("COMPLETED 狀態的 onProgressUpdated 應顯示傳送完成訊息")
        void progressCompleted_showsCompletedMessage(@TempDir Path tempDir) throws IOException {
            Path file = Files.createFile(tempDir.resolve("done.bin"));
            Files.writeString(file, "data");

            FileTask task = new FileTask("t2", file.toString(), "done.bin", 100L);
            task.setBytesTransferred(100L);
            task.setStatus(FileTask.Status.COMPLETED);

            sendSpy.onExecute = () -> sendSpy.lastListener.onProgressUpdated(task);
            controller.sendFile("192.168.1.2:8080", file.toString());

            assertTrue(output().contains("傳送完成"),
                    "COMPLETED 狀態應顯示傳送完成訊息");
        }

        @Test
        @DisplayName("onError 回呼應顯示傳送失敗訊息與原因，且不 crash")
        void onError_showsFailureMessageAndNoCrash(@TempDir Path tempDir) throws IOException {
            Path file = Files.createFile(tempDir.resolve("fragile.dat"));
            Files.writeString(file, "data");

            FileTask task = new FileTask("t3", file.toString(), "fragile.dat", 200L);
            task.setStatus(FileTask.Status.FAILED);

            sendSpy.onExecute = () ->
                    sendSpy.lastListener.onError(task, "Connection reset by peer");

            assertDoesNotThrow(() -> controller.sendFile("192.168.1.3:8080", file.toString()),
                    "onError 回呼不應讓程式 crash");

            String o = output();
            assertTrue(o.contains("傳送失敗"),              "應顯示傳送失敗標籤");
            assertTrue(o.contains("Connection reset by peer"), "應顯示原始錯誤原因");
        }

        @Test
        @DisplayName("onError 傳入 null task 時不應拋 NullPointerException")
        void onError_nullTask_doesNotThrow(@TempDir Path tempDir) throws IOException {
            Path file = Files.createFile(tempDir.resolve("null.bin"));
            Files.writeString(file, "x");

            sendSpy.onExecute = () ->
                    sendSpy.lastListener.onError(null, "Unexpected EOF");

            assertDoesNotThrow(() -> controller.sendFile("1.2.3.4:8080", file.toString()),
                    "null task 不應拋 NullPointerException");
        }
    }

    // =========================================================================
    // sendFile() — UseCase 例外處理測試群
    // =========================================================================

    @Nested
    @DisplayName("sendFile() — UseCase 例外處理")
    class ExceptionHandlingTests {

        @Test
        @DisplayName("UseCase 拋 IllegalArgumentException 應顯示錯誤訊息且不 crash")
        void illegalArgException_showsErrorAndNoCrash(@TempDir Path tempDir) throws IOException {
            Path file = Files.createFile(tempDir.resolve("test.txt"));
            Files.writeString(file, "content");

            sendSpy.toThrow = new IllegalArgumentException("Target IP 192.168.1.5 not in active peers");

            assertDoesNotThrow(() -> controller.sendFile("192.168.1.5:8080", file.toString()),
                    "IllegalArgumentException 不應讓程式 crash");
            assertTrue(output().contains("[錯誤]"), "應顯示 [錯誤] 標籤");
        }

        @Test
        @DisplayName("UseCase 拋非預期 RuntimeException 應顯示錯誤訊息且不 crash")
        void runtimeException_showsErrorAndNoCrash(@TempDir Path tempDir) throws IOException {
            Path file = Files.createFile(tempDir.resolve("oops.bin"));
            Files.writeString(file, "data");

            sendSpy.toThrow = new RuntimeException("Out of memory");

            assertDoesNotThrow(() -> controller.sendFile("192.168.1.1:8080", file.toString()),
                    "RuntimeException 不應讓程式 crash");
            assertTrue(output().contains("[錯誤]"), "應顯示 [錯誤] 標籤");
        }
    }

    // =========================================================================
    // 架構守衛：確認 Controller 不依賴 Infrastructure 層
    // =========================================================================

    @Nested
    @DisplayName("架構守衛 — 依賴邊界")
    class ArchitectureGuardTests {

        @Test
        @DisplayName("Controller 欄位型別不應來自 infrastructure 套件")
        void controllerFields_doNotDependOnInfrastructure() throws Exception {
            Class<?> cls = Class.forName("com.airdrop.adapter.controller.CliController");
            for (java.lang.reflect.Field field : cls.getDeclaredFields()) {
                String typeName = field.getType().getName();
                assertFalse(typeName.startsWith("com.airdrop.infrastructure"),
                        "Controller 欄位不應來自 infrastructure 套件，但發現：" + typeName);
            }
        }

        @Test
        @DisplayName("Controller 建構子參數型別不應來自 infrastructure 套件")
        void controllerConstructors_doNotAcceptInfrastructureParams() throws Exception {
            Class<?> cls = Class.forName("com.airdrop.adapter.controller.CliController");
            for (java.lang.reflect.Constructor<?> ctor : cls.getDeclaredConstructors()) {
                for (Class<?> param : ctor.getParameterTypes()) {
                    assertFalse(param.getName().startsWith("com.airdrop.infrastructure"),
                            "建構子參數不應來自 infrastructure 套件：" + param.getName());
                }
            }
        }
    }
}
