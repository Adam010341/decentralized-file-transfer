```mermaid
sequenceDiagram
    participant CLI as CLI (Member C: UI & Adapter Layer)
    participant SendFileUseCase as SendFileUseCase (Member B: Core Domain & Brain)
    participant NettyGateway as NettyGateway (Member A: Network Infrastructure)

    CLI->>SendFileUseCase: execute(ip, filePath)
    
    Note over SendFileUseCase: 驗證：檢查內部記憶體註冊表<br/>確認目標 IP 是否為有效且活躍的 Peer
    Note over SendFileUseCase: 實體建立：建立 FileTask 實體<br/>(初始狀態設定為 WAITING)
    
    SendFileUseCase->>NettyGateway: sendFile(targetPeer, fileTask, fileTransferListener)
    Note over NettyGateway: 網路動作：開啟 TCP 通道<br/>開始進行分塊/零拷貝 (Zero-copy) 檔案傳輸
    
    loop 進度更新 (Progress Update)
        NettyGateway-->>SendFileUseCase: fileTransferListener.onProgressUpdated(task)
        Note over CLI: UI 渲染：CLI 從 task 讀取進度<br/>並更新終端機進度條
    end
    
    alt 傳輸錯誤 (例如 TCP 連線中斷)
        NettyGateway-->>SendFileUseCase: fileTransferListener.onError()
    end