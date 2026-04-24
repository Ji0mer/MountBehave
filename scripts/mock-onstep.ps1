param(
    [int]$Port = 9999
)

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any, $Port)
$listener.Start()
Write-Host "Mock OnStep listening on TCP port $Port"
Write-Host "Use 10.0.2.2:$Port from the Android emulator, or this PC's LAN IP from a phone."

try {
    while ($true) {
        $client = $listener.AcceptTcpClient()
        $remote = $client.Client.RemoteEndPoint
        Write-Host "Client connected: $remote"
        $stream = $client.GetStream()
        $buffer = New-Object byte[] 1
        $command = New-Object System.Text.StringBuilder

        try {
            while ($client.Connected -and $stream.Read($buffer, 0, 1) -gt 0) {
                $char = [char]$buffer[0]
                [void]$command.Append($char)
                if ($char -eq '#') {
                    $text = $command.ToString()
                    $command.Clear() | Out-Null
                    Write-Host "RX $text"

                    $replyText = $null
                    if ($text -eq ':GVP#') {
                        $replyText = 'OnStep Mock#'
                    } elseif ($text -eq ':GR#') {
                        $replyText = '06:45:09#'
                    } elseif ($text -eq ':GD#') {
                        $replyText = '-16*42:58#'
                    } elseif ($text -eq ':MS#') {
                        $replyText = '0#'
                    } elseif ($text -eq ':CM#') {
                        $replyText = 'N/A#'
                    } elseif ($text -match '^:A[123+]#$' -or $text -eq ':AW#') {
                        $replyText = '1#'
                    } elseif ($text -match '^:T[eod12QLS]#$') {
                        $replyText = '1#'
                    }

                    if ($replyText) {
                        $reply = [System.Text.Encoding]::ASCII.GetBytes($replyText)
                        $stream.Write($reply, 0, $reply.Length)
                        $stream.Flush()
                        Write-Host "TX $replyText"
                    }
                }
            }
        } finally {
            $stream.Dispose()
            $client.Close()
            Write-Host "Client disconnected: $remote"
        }
    }
} finally {
    $listener.Stop()
}
