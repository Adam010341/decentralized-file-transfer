package com.airdrop.infrastructure.cli;

import com.airdrop.adapter.controller.CliController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * PicocliRunner 單元測試
 *
 * <p><b>測試策略：Mockito {@code @Mock CliController}</b></p>
 * <p>
 * {@link CliController} 是使用者定義的具體類別（非 JDK 內建介面），
 * 在 Java 21 環境下 Mockito 5.5.0 可透過 byte-buddy instrumentation 建立代理。
 * </p>
 *
 * <p><b>測試重點：</b></p>
 * <ul>
 *   <li>各子指令（discover / list / send）是否呼叫 {@link CliController} 正確的方法</li>
 *   <li>send 指令缺少必要選項時 Picocli 是否回傳非零 exit code，且不呼叫 Controller</li>
 *   <li>{@code --help} / 無參數是否回傳 0 且不呼叫 Controller</li>
 *   <li>{@code run()} 不應呼叫 {@code System.exit()}（測試環境可安全執行）</li>
 *   <li>架構守衛：PicocliRunner 不直接持有 Netty 或 UseCase 物件</li>
 * </ul>
 *
 * <p><b>Picocli exit code 規範（4.x）：</b></p>
 * <ul>
 *   <li>{@code 0} — 指令正常完成</li>
 *   <li>{@code 2} — 使用者輸入錯誤（缺少必要選項、未知子指令）</li>
 *   <li>{@code 1} — 執行時例外</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PicocliRunnerTest {

    @Mock
    private CliController cliController;

    private PicocliRunner runner;

    @BeforeEach
    void setUp() {
        runner = new PicocliRunner(cliController);
    }

    // =========================================================================
    // 建構子驗證
    // =========================================================================

    @Nested
    @DisplayName("建構子驗證")
    class ConstructorTests {

        @Test
        @DisplayName("null controller 應拋 IllegalArgumentException")
        void nullController_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> new PicocliRunner(null));
        }

        @Test
        @DisplayName("合法 controller 應成功建立 Runner 實例")
        void validController_createsRunner() {
            assertDoesNotThrow(() -> new PicocliRunner(cliController));
        }
    }

    // =========================================================================
    // discover 子指令
    // =========================================================================

    @Nested
    @DisplayName("discover 子指令")
    class DiscoverCommandTests {

        @Test
        @DisplayName("discover 應呼叫 cliController.discoverPeers() 恰好一次")
        void discover_callsDiscoverPeers_once() {
            runner.run(new String[]{"discover"});
            verify(cliController, times(1)).discoverPeers();
        }

        @Test
        @DisplayName("discover 應回傳 exit code 0")
        void discover_returnsZero() {
            int exitCode = runner.run(new String[]{"discover"});
            assertEquals(0, exitCode);
        }

        @Test
        @DisplayName("discover 不應呼叫 listPeers 或 sendFile")
        void discover_neverCallsOtherMethods() {
            runner.run(new String[]{"discover"});
            verify(cliController, never()).listPeers();
            verify(cliController, never()).sendFile(any(), any());
        }
    }

    // =========================================================================
    // list 子指令
    // =========================================================================

    @Nested
    @DisplayName("list 子指令")
    class ListCommandTests {

        @Test
        @DisplayName("list 應呼叫 cliController.listPeers() 恰好一次")
        void list_callsListPeers_once() {
            runner.run(new String[]{"list"});
            verify(cliController, times(1)).listPeers();
        }

        @Test
        @DisplayName("list 應回傳 exit code 0")
        void list_returnsZero() {
            int exitCode = runner.run(new String[]{"list"});
            assertEquals(0, exitCode);
        }

        @Test
        @DisplayName("list 不應呼叫 discoverPeers 或 sendFile")
        void list_neverCallsOtherMethods() {
            runner.run(new String[]{"list"});
            verify(cliController, never()).discoverPeers();
            verify(cliController, never()).sendFile(any(), any());
        }
    }

    // =========================================================================
    // send 子指令 — 成功路徑
    // =========================================================================

    @Nested
    @DisplayName("send 子指令 — 成功路徑")
    class SendCommandSuccessTests {

        @Test
        @DisplayName("send --peer --file 應呼叫 sendFile() 並回傳 0")
        void send_withLongOptions_callsSendFile_returnsZero() {
            int exitCode = runner.run(new String[]{
                    "send", "--peer", "192.168.1.5:8080", "--file", "/path/to/file.txt"
            });
            assertEquals(0, exitCode);
            verify(cliController, times(1)).sendFile("192.168.1.5:8080", "/path/to/file.txt");
        }

        @Test
        @DisplayName("send -p -f 短選項應呼叫 sendFile() 並回傳 0")
        void send_withShortOptions_callsSendFile_returnsZero() {
            int exitCode = runner.run(new String[]{
                    "send", "-p", "10.0.0.1:9000", "-f", "/data/img.png"
            });
            assertEquals(0, exitCode);
            verify(cliController, times(1)).sendFile("10.0.0.1:9000", "/data/img.png");
        }

        @Test
        @DisplayName("send 應將 peerId 與 filePath 原封不動傳給 Controller")
        void send_passesExactArgsToController() {
            String peerId   = "192.168.100.200:12345";
            String filePath = "/home/user/documents/report.pdf";

            runner.run(new String[]{"send", "-p", peerId, "-f", filePath});

            verify(cliController).sendFile(peerId, filePath);
        }

        @Test
        @DisplayName("send 成功時不應呼叫 discoverPeers 或 listPeers")
        void send_neverCallsOtherMethods() {
            runner.run(new String[]{"send", "-p", "1.2.3.4:8080", "-f", "/any/file"});
            verify(cliController, never()).discoverPeers();
            verify(cliController, never()).listPeers();
        }
    }

    // =========================================================================
    // send 子指令 — 缺少必要選項
    // =========================================================================

    @Nested
    @DisplayName("send 子指令 — 缺少必要選項")
    class SendCommandValidationTests {

        @Test
        @DisplayName("send 缺少 --peer 應回傳非零 exit code")
        void send_missingPeer_returnsNonZero() {
            int exitCode = runner.run(new String[]{"send", "--file", "/path/file.txt"});
            assertNotEquals(0, exitCode,
                    "缺少 --peer 時 Picocli 應回傳使用者錯誤 exit code（2）");
        }

        @Test
        @DisplayName("send 缺少 --file 應回傳非零 exit code")
        void send_missingFile_returnsNonZero() {
            int exitCode = runner.run(new String[]{"send", "--peer", "192.168.1.5:8080"});
            assertNotEquals(0, exitCode,
                    "缺少 --file 時 Picocli 應回傳使用者錯誤 exit code（2）");
        }

        @Test
        @DisplayName("send 缺少所有選項應回傳非零 exit code")
        void send_missingAllOptions_returnsNonZero() {
            int exitCode = runner.run(new String[]{"send"});
            assertNotEquals(0, exitCode);
        }

        @Test
        @DisplayName("send 缺少必要選項時不應呼叫任何 Controller 方法")
        void send_missingOptions_neverCallsController() {
            runner.run(new String[]{"send"});
            verify(cliController, never()).discoverPeers();
            verify(cliController, never()).listPeers();
            verify(cliController, never()).sendFile(any(), any());
        }

        @Test
        @DisplayName("send 缺少 --peer 時不應呼叫 sendFile")
        void send_missingPeer_neverCallsSendFile() {
            runner.run(new String[]{"send", "--file", "/path/file.txt"});
            verify(cliController, never()).sendFile(any(), any());
        }
    }

    // =========================================================================
    // help 與 version
    // =========================================================================

    @Nested
    @DisplayName("--help 與 -h 選項")
    class HelpAndVersionTests {

        @Test
        @DisplayName("--help 應回傳 0")
        void help_returnsZero() {
            assertEquals(0, runner.run(new String[]{"--help"}));
        }

        @Test
        @DisplayName("-h 短選項 help 應回傳 0")
        void shortHelp_returnsZero() {
            assertEquals(0, runner.run(new String[]{"-h"}));
        }

        @Test
        @DisplayName("discover --help 應回傳 0")
        void discoverHelp_returnsZero() {
            assertEquals(0, runner.run(new String[]{"discover", "--help"}));
        }

        @Test
        @DisplayName("list --help 應回傳 0")
        void listHelp_returnsZero() {
            assertEquals(0, runner.run(new String[]{"list", "--help"}));
        }

        @Test
        @DisplayName("send --help 應回傳 0（即使未提供必要選項）")
        void sendHelp_returnsZero_evenWithoutRequiredOptions() {
            assertEquals(0, runner.run(new String[]{"send", "--help"}));
        }

        @Test
        @DisplayName("--help 不應呼叫任何 Controller 方法")
        void help_neverCallsController() {
            runner.run(new String[]{"--help"});
            verify(cliController, never()).discoverPeers();
            verify(cliController, never()).listPeers();
            verify(cliController, never()).sendFile(any(), any());
        }

        @Test
        @DisplayName("discover --help 不應呼叫 discoverPeers")
        void discoverHelp_neverCallsDiscoverPeers() {
            runner.run(new String[]{"discover", "--help"});
            verify(cliController, never()).discoverPeers();
        }
    }

    // =========================================================================
    // 邊界情況
    // =========================================================================

    @Nested
    @DisplayName("邊界情況")
    class EdgeCaseTests {

        @Test
        @DisplayName("無參數應回傳 0（顯示主指令 help）")
        void noArgs_returnsZero() {
            assertEquals(0, runner.run(new String[]{}),
                    "無子指令時 AirDropCommand.run() 印出 help，應正常結束回傳 0");
        }

        @Test
        @DisplayName("無參數不應呼叫任何 Controller 方法")
        void noArgs_neverCallsController() {
            runner.run(new String[]{});
            verify(cliController, never()).discoverPeers();
            verify(cliController, never()).listPeers();
            verify(cliController, never()).sendFile(any(), any());
        }

        @Test
        @DisplayName("null args 不應拋例外")
        void nullArgs_doesNotThrow() {
            assertDoesNotThrow(() -> runner.run(null));
        }

        @Test
        @DisplayName("null args 應回傳 0（視為無參數）")
        void nullArgs_treatedAsNoArgs_returnsZero() {
            assertEquals(0, runner.run(null));
        }

        @Test
        @DisplayName("未知子指令應回傳非零 exit code")
        void unknownSubcommand_returnsNonZero() {
            assertNotEquals(0, runner.run(new String[]{"unknowncommand"}),
                    "未知指令 Picocli 應回傳使用者錯誤 exit code");
        }

        @Test
        @DisplayName("未知子指令不應呼叫任何 Controller 方法")
        void unknownSubcommand_neverCallsController() {
            runner.run(new String[]{"unknowncommand"});
            verify(cliController, never()).discoverPeers();
            verify(cliController, never()).listPeers();
            verify(cliController, never()).sendFile(any(), any());
        }

        @Test
        @DisplayName("run() 不應呼叫 System.exit()（測試環境可安全執行）")
        void run_doesNotCallSystemExit() {
            // 若 run() 內部呼叫了 System.exit()，此 JVM 會中止
            // 測試本身存活即代表 System.exit() 未被呼叫
            assertDoesNotThrow(() -> {
                int code = runner.run(new String[]{"list"});
                assertNotNull(Integer.valueOf(code));
            });
        }
    }

    // =========================================================================
    // 架構守衛
    // =========================================================================

    @Nested
    @DisplayName("架構守衛 — 依賴邊界")
    class ArchitectureGuardTests {

        @Test
        @DisplayName("PicocliRunner 欄位不應直接依賴 Netty 類別")
        void picocliRunner_doesNotDependOnNetty() throws Exception {
            Class<?> cls = Class.forName("com.airdrop.infrastructure.cli.PicocliRunner");
            for (java.lang.reflect.Field field : cls.getDeclaredFields()) {
                String typeName = field.getType().getName();
                assertFalse(typeName.toLowerCase().contains("netty"),
                        "PicocliRunner 不應直接持有 Netty 物件：" + typeName);
            }
        }

        @Test
        @DisplayName("PicocliRunner 欄位不應直接持有 UseCase 具體類別")
        void picocliRunner_doesNotDependOnUseCases() throws Exception {
            Class<?> cls = Class.forName("com.airdrop.infrastructure.cli.PicocliRunner");
            for (java.lang.reflect.Field field : cls.getDeclaredFields()) {
                String typeName = field.getType().getName();
                assertFalse(typeName.contains("UseCase"),
                        "PicocliRunner 應透過 CliController 間接使用 UseCase，不應直接持有：" + typeName);
            }
        }

        @Test
        @DisplayName("PicocliRunner 非靜態欄位應只有 CliController 類型")
        void picocliRunner_onlyHasCliControllerField() throws Exception {
            Class<?> cls = Class.forName("com.airdrop.infrastructure.cli.PicocliRunner");
            for (java.lang.reflect.Field field : cls.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                String typeName = field.getType().getName();
                assertTrue(typeName.contains("CliController"),
                        "PicocliRunner 的非靜態欄位應只有 CliController，但發現：" + typeName);
            }
        }
    }
}
