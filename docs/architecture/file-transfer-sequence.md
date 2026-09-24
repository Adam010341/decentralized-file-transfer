# Send Flow

This diagram follows one `send` command through the layers, from the CLI on the sending node to the file on the receiving node's disk. It adds the validation and error paths that the overview in the [README](../../README.md#how-it-works) leaves out. The IP address, port and file name are example values, and the peer is assumed to have been found by an earlier `discover`.

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant P as PicocliRunner
    participant C as CliController
    participant S as SendFileUseCase
    participant R as DiscoverPeersUseCase
    participant G as NettyNetworkGateway<br/>+ NettyClient
    participant B as Receiving node<br/>(NettyServer)

    U->>P: send -p 192.168.1.5:40123 -f a.pdf
    P->>C: sendFile(peerId, filePath)
    Note over C: checks that the file exists, is not a folder<br/>and is readable, then takes the IP from peerId
    C->>S: execute(ip, absolutePath, listener)
    S->>R: getActivePeers()
    R-->>S: active peers
    alt no active peer has this IP
        S-->>C: IllegalArgumentException
        C-->>U: error message
    else peer found, its port comes from the peer table
        Note over S: creates FileTask(id, path, name, size)<br/>with status WAITING
        S->>G: sendFile(peer, task, listener)
        Note over C,G: sendFile returns at once. The transfer runs on a<br/>Netty event-loop thread and reports through the listener.
        G->>B: TCP connect 192.168.1.5:40123
        alt connection fails
            G-->>C: onError(task, cause)
            C-->>U: error message
        else connected
            G->>B: [4-byte length] id|a.pdf|size|senderName
            Note over B: MetadataHandler parses the header,<br/>swaps in FileWriteHandler,<br/>which writes ./downloaded_a.pdf
            G->>B: file bytes via DefaultFileRegion (zero-copy)
            loop while bytes are written
                G-->>C: onProgressUpdated(task)
                C-->>U: progress bar
            end
            Note over G: all bytes written, status COMPLETED<br/>(the receiver sends no ACK)
            G-->>C: onProgressUpdated(task)
            C-->>U: completion message
            G-xB: close connection
            Note over B: on close: COMPLETED if all declared<br/>bytes arrived, otherwise onError
        end
    end
```

`CliController` only checks the peer ID's `ip:port` shape; `SendFileUseCase` matches the IP address against the active peers, so the port typed in the ID is not used. `SendFileUseCase` also checks the file again before it creates the `FileTask`. On the receiving node, `App` registers the `FileTransferListener` that prints receive progress and, once the task is `COMPLETED`, the path of the saved file.
