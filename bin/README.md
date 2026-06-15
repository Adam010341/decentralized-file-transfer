# p2p-file-transfer
# 🚀 LAN P2P File Transfer (AirDrop Clone)

這是一個基於區域網路（LAN）的 P2P 檔案傳輸命令列工具。專案採取純點對點架構，利用 UDP 多播進行節點自動發現，並透過 TCP 進行高效的檔案傳輸，實現類似 AirDrop 的「零配置（Zero-configuration）」便利性。

本專案為軟體工程期末專題，全程採用 **Vibe Coding**（結合 Antigravity 與 Gemini/Claude AI 代理）輔助開發，並嚴格遵循 **整潔架構 (Clean Architecture)** 原則。

## 🛠 技術選型

* **開發環境：** VSCode (Java Extension Pack)
* **核心框架：**
* **Netty：** 負責底層非同步 UDP/TCP 網路通訊、高併發處理與大檔案 I/O。
* **Picocli：** 負責建構現代化、易用的終端機互動介面 (CLI)。

## 🏗 系統架構與團隊分工 (由內而外)

系統架構嚴格依照 Clean Architecture 的同心圓法則設計。依賴方向**僅能由外向內**，確保核心商業邏輯的絕對純淨，不受任何外部框架或 UI 變動的干擾。

### 1. 核心層 (Center): Entities & Use Cases

**[ 🧑‍💻 Member B 負責：系統大腦與核心業務邏輯 ]**

* **Domain (實體層)：** 封裝最核心的資料結構與狀態，不依賴任何外部套件。
* **UseCase (應用例層)：** 定義 P2P 傳輸的業務流程。只知道抽象介面，完全不知道底層網路是怎麼傳遞封包的。

### 2. 轉接層 (Middle): Interface Adapters

**[ 🧑‍💻 Member C & Member A 協作：介面與資料轉換 ]**

* 負責將外層（CLI 輸入或網路封包）的資料轉換為內層 UseCase 容易理解的格式。
* Member A 負責定義網路通訊的抽象介面（`NetworkGateway`）。
* Member C 負責攔截終端機指令（`CliController`）並呼叫對應的 UseCase。

### 3. 外部細節層 (Outer): Frameworks & Drivers

**[ 🧑‍💻 Member A & Member C 協作：底層實作與基礎建設 ]**

* **網路驅動 (Member A)：** 實作 Netty 伺服器與客戶端，處理最困難的非同步 I/O、UDP 多播與 TCP 通訊。
* **UI 驅動 (Member C)：** 處理 Picocli 的具體設定，以及進度條的終端機渲染。

---

## 📂 專案目錄結構 (Inside-Out View)

```text
p2p-file-transfer/
├── src/main/java/com/airdrop/
│   ├── domain/                           [Layer 1: 核心實體層] 
│   │   ├── model/Peer.java               (Member B: 節點實體)
│   │   └── model/FileTask.java           (Member B: 傳輸任務實體)
│   │
│   ├── usecase/                          [Layer 2: 應用例層] 
│   │   ├── port/in/NetworkGateway.java   (Member B 定義: 呼叫外層網路的抽象介面)
│   │   ├── port/out/PeerDiscoveryListener.java (Member B 定義: 節點發現的回呼介面)
│   │   ├── port/out/FileTransferListener.java (Member B 定義: 傳輸進度的回呼介面)
│   │   ├── DiscoverPeersUseCase.java     (Member B: 節點發現與剔除邏輯)
│   │   └── SendFileUseCase.java          (Member B: 協調傳輸流程)
│   │
│   ├── adapter/                          [Layer 3: 介面轉接層] 
│   │   └── controller/CliController.java (Member C: 攔截並解析終端機指令)
│   │
│   ├── infrastructure/                   [Layer 4: 框架與驅動層] 
│   │   ├── netty/NettyServer.java        (Member A: TCP 接收與 UDP 監聽)
│   │   ├── netty/NettyClient.java        (Member A: TCP 發送與 UDP 多播)
│   │   ├── netty/NettyNetworkGateway.java (Member A: Netty 實作)
│   │   └── cli/PicocliRunner.java        (Member C: 終端機 UI 渲染細節)
│   │
│   └── App.java                          (Member C: 程式進入點與依賴組裝)
│
└── src/test/java/com/airdrop/            [測試層]
    ├── usecase/SendFileUseCaseTest.java  (Member B: 測試業務邏輯)
    └── infrastructure/NettyTest.java     (Member A: 測試網路連線與大檔案邊界)

```

---

## 🤝 Vibe Coding 與協作規範

為真實還原專業軟體工程環境並記錄 AI 協作歷程，本專案嚴格執行以下版控與 Code Review 規範：

1. **禁止直接 Push 至 Main/Dev：** 所有開發必須基於各自負責範圍切出 Feature Branch（例如：`feat/usecase-sendfile` 或 `feat/netty-udp`）。
2. **AI 協作軌跡 (Prompt Engineering)：** * 使用 AI 輔助生成的程式碼，必須在 Pull Request (PR) 的 Description 中**完整貼上使用的 AI Prompt 與技術要求**。
* 展示我們如何透過精確的 Prompt 要求 AI 遵守「單一職責原則」，避免生成難以維護的義大利麵條程式碼 (Spaghetti code)。


3. **強制 Code Review：** PR 必須經過組員互相 Review。重點審查**架構邊界是否越界**（例如：嚴禁 UI Controller 直接呼叫 Netty 實體），確認無誤後方可 Merge 進入 `dev` 分支。

## 🎯 進階迭代與除錯亮點 (Roadmap)

第一階段 MVP（最小可行性產品）跑通後，專案將透過 AI 協作進行以下進階迭代，這些歷程將收錄於期末簡報中：

* **效能優化：** 將基礎的位元組傳輸重構升級為 Netty 的 **「零拷貝 (Zero-copy)」** 技術與多執行緒分塊傳輸。
* **科學除錯 (Scientific Debugging)：** 記錄在大檔案傳輸過程中遭遇的底層 Bug（如 OOM 或 TCP 黏包），並展示如何透過假設與驗證的科學方法進行收斂與修復。
