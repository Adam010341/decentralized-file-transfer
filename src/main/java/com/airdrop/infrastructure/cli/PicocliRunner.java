package com.airdrop.infrastructure.cli;

import com.airdrop.adapter.controller.CliController;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * PicocliRunner — Frameworks &amp; Drivers 層（Clean Architecture 最外層）
 *
 * <p>
 * <b>為什麼屬於 Infrastructure 層？</b>
 * </p>
 * <p>
 * Picocli 是一個具體的 CLI 框架（外部工具），屬於「框架與驅動程式」層。
 * 它的職責是將終端機的原始字串輸入（{@code argv}）解析成結構化的指令物件，
 * 再轉發給 Interface Adapters 層的 {@link CliController}。
 * PicocliRunner 本身不含任何業務邏輯，只做「格式轉換」與「UI 呈現」。
 * </p>
 *
 * <p>
 * <b>為什麼可以依賴 CliController？</b>
 * </p>
 * <p>
 * 在 Clean Architecture 中，依賴方向是由外層指向內層：
 * <pre>
 *   Frameworks &amp; Drivers（本類別）
 *       ↓ 依賴
 *   Interface Adapters（CliController）
 *       ↓ 依賴
 *   Use Cases（DiscoverPeersUseCase / SendFileUseCase）
 *       ↓ 依賴
 *   Domain（Peer / FileTask）
 * </pre>
 * PicocliRunner 位於最外層，依賴 CliController（第三層）完全符合依賴規則。
 * </p>
 *
 * <p>
 * <b>為什麼不直接呼叫 UseCase 或 Netty？</b>
 * </p>
 * <p>
 * 若 PicocliRunner 直接呼叫 {@code DiscoverPeersUseCase} 或
 * {@code NettyNetworkGateway}，會導致：
 * <ul>
 * <li>職責重疊：PicocliRunner 既做 CLI 解析又做業務協調，違反單一職責原則</li>
 * <li>層次跳越：跨越 Interface Adapters 層直接呼叫 Use Case，架構邊界崩潰</li>
 * <li>難以測試：測試時必須啟動真實網路，無法注入測試替身</li>
 * </ul>
 * 正確做法是將業務協調的責任完整交給 {@link CliController}。
 * </p>
 *
 * <p>
 * <b>可測試性設計：</b>
 * </p>
 * <p>
 * {@link #run(String[])} 回傳 Picocli 的 exit code，
 * 不在此處呼叫 {@code System.exit()}，由 {@code App.main()} 決定是否結束 JVM。
 * 測試時可直接呼叫 {@code run(args)} 並斷言回傳值。
 * </p>
 *
 * <p>
 * <b>支援的 CLI 指令：</b>
 * </p>
 * <pre>
 *   airdrop --help
 *   airdrop discover
 *   airdrop list
 *   airdrop send --peer 192.168.1.5:8080 --file /path/to/file.txt
 *   airdrop send -p 192.168.1.5:8080 -f /path/to/file.txt
 * </pre>
 */
public class PicocliRunner {

    private final CliController cliController;

    /**
     * 建構子，注入 CliController。
     *
     * @param cliController Interface Adapters 層的 Controller，負責業務邏輯協調
     * @throws IllegalArgumentException 若 cliController 為 null
     */
    public PicocliRunner(CliController cliController) {
        if (cliController == null) {
            throw new IllegalArgumentException("cliController 不可為 null");
        }
        this.cliController = cliController;
    }

    /**
     * 解析並執行 CLI 指令，回傳 Picocli exit code。
     *
     * <p>
     * 常見 exit code：
     * <ul>
     * <li>{@code 0} — 指令正常完成</li>
     * <li>{@code 1} — 使用者輸入錯誤（缺少必要參數等）</li>
     * <li>{@code 2} — 執行時例外</li>
     * </ul>
     * 呼叫者（{@code App.main()}）可依此決定是否 {@code System.exit(exitCode)}。
     * </p>
     *
     * @param args 終端機原始參數（來自 {@code main(String[] args)}）
     * @return Picocli exit code
     */
    public int run(String[] args) {
        CommandLine commandLine = buildCommandLine();
        return commandLine.execute(args != null ? args : new String[0]);
    }

    /**
     * 組裝 Picocli CommandLine 物件樹（主指令 + 三個子指令）。
     *
     * <p>
     * 子指令以程式碼方式注入 {@link CliController}，
     * 使每個子指令都能直接呼叫 Controller 對應的方法。
     * </p>
     *
     * @return 組裝完成的 {@link CommandLine}
     */
    private CommandLine buildCommandLine() {
        CommandLine commandLine = new CommandLine(new AirDropCommand());
        commandLine.addSubcommand("discover", new DiscoverCommand(cliController));
        commandLine.addSubcommand("list",     new ListCommand(cliController));
        commandLine.addSubcommand("send",     new SendCommand(cliController));
        return commandLine;
    }

    // =========================================================================
    // 主指令：airdrop
    // =========================================================================

    /**
     * 主指令 {@code airdrop}。
     *
     * <p>
     * 當使用者未輸入任何子指令時，自動印出 usage help。
     * {@code mixinStandardHelpOptions = true} 啟用 {@code --help} / {@code -h}
     * 與 {@code --version} / {@code -V} 兩個內建選項。
     * </p>
     */
    @Command(
        name        = "airdrop",
        description = {
            "LAN P2P File Transfer — AirDrop-like tool for local network.",
            "",
            "Run a subcommand to get started. Use --help on any subcommand for details."
        },
        mixinStandardHelpOptions = true,
        version     = "airdrop 1.0"
    )
    static class AirDropCommand implements Runnable {

        /**
         * Picocli 自動注入此欄位，讓指令可取得自身的 CommandLine 物件。
         * 用於在無子指令時印出 usage help。
         */
        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        /**
         * 使用者未輸入子指令時執行：印出 usage help。
         */
        @Override
        public void run() {
            spec.commandLine().usage(System.out);
        }
    }

    // =========================================================================
    // 子指令：discover
    // =========================================================================

    /**
     * 子指令 {@code airdrop discover}。
     *
     * <p>
     * 啟動 LAN 節點搜尋，並在搜尋結束後印出發現的裝置清單。
     * 底層搜尋機制（UDP multicast）由 UseCase 透過 NetworkGateway 執行，
     * 本指令只負責觸發 Controller 方法。
     * </p>
     *
     * <p>使用方式：{@code airdrop discover}</p>
     */
    @Command(
        name        = "discover",
        description = "Search for available peers on the local network via UDP multicast.",
        mixinStandardHelpOptions = true
    )
    static class DiscoverCommand implements Runnable {

        private final CliController cliController;

        DiscoverCommand(CliController cliController) {
            this.cliController = cliController;
        }

        /**
         * 呼叫 {@link CliController#discoverPeers()} 啟動搜尋流程。
         * 可依實際 CliController API 調整方法名稱。
         */
        @Override
        public void run() {
            cliController.discoverPeers();
        }
    }

    // =========================================================================
    // 子指令：list
    // =========================================================================

    /**
     * 子指令 {@code airdrop list}。
     *
     * <p>
     * 顯示目前已發現且仍在線（未逾時）的節點清單快照。
     * 此指令不重新觸發搜尋，僅讀取 UseCase 快取的活躍節點。
     * 若使用者尚未執行 {@code discover}，清單可能為空。
     * </p>
     *
     * <p>使用方式：{@code airdrop list}</p>
     */
    @Command(
        name        = "list",
        description = {
            "List all currently discovered (active) peers.",
            "Note: Run 'airdrop discover' first to populate the peer list."
        },
        mixinStandardHelpOptions = true
    )
    static class ListCommand implements Runnable {

        private final CliController cliController;

        ListCommand(CliController cliController) {
            this.cliController = cliController;
        }

        /**
         * 呼叫 {@link CliController#listPeers()} 印出快取的節點清單。
         * 可依實際 CliController API 調整方法名稱。
         */
        @Override
        public void run() {
            cliController.listPeers();
        }
    }

    // =========================================================================
    // 子指令：send
    // =========================================================================

    /**
     * 子指令 {@code airdrop send}。
     *
     * <p>
     * 將指定本地檔案傳送至目標節點。
     * peerId 格式為 {@code ip:port}（如 {@code 192.168.1.5:8080}），
     * 可從 {@code airdrop list} 的輸出取得。
     * </p>
     *
     * <p>
     * <b>輸入驗證策略：</b><br>
     * PicocliRunner 只負責確認使用者有提供 {@code --peer} 與 {@code --file} 兩個選項。
     * 參數值的合法性驗證（peerId 格式、檔案是否存在等）交由 {@link CliController} 處理，
     * 遵循單一職責原則。
     * </p>
     *
     * <p>使用方式：</p>
     * <pre>
     *   airdrop send --peer 192.168.1.5:8080 --file /path/to/file.txt
     *   airdrop send -p 192.168.1.5:8080 -f /path/to/file.txt
     * </pre>
     */
    @Command(
        name        = "send",
        description = "Send a file to a specified peer.",
        mixinStandardHelpOptions = true
    )
    static class SendCommand implements Runnable {

        private final CliController cliController;

        /**
         * 目標節點 ID，格式為 {@code ip:port}，例如 {@code 192.168.1.5:8080}。
         * 透過 {@code airdrop list} 取得可用節點的 peer ID。
         */
        @Option(
            names       = {"-p", "--peer"},
            required    = true,
            description = "Target peer ID (format: ip:port, e.g. 192.168.1.5:8080). "
                        + "Use 'airdrop list' to see available peers."
        )
        private String peerId;

        /**
         * 欲傳送的本地檔案路徑（絕對路徑或相對路徑皆可）。
         * 路徑有效性由 CliController 驗證。
         */
        @Option(
            names       = {"-f", "--file"},
            required    = true,
            description = "Path to the local file to send (absolute or relative)."
        )
        private String filePath;

        SendCommand(CliController cliController) {
            this.cliController = cliController;
        }

        /**
         * 將解析後的參數轉發給 {@link CliController#sendFile(String, String)}。
         * 可依實際 CliController API 調整方法名稱與簽名。
         */
        @Override
        public void run() {
            cliController.sendFile(peerId, filePath);
        }
    }
}
