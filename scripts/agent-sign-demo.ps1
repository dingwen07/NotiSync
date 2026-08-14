#Requires -Version 5.1
<#
.SYNOPSIS
    Sign data with a key from an SSH agent by speaking the raw SSH agent
    protocol over a chosen socket (default: the Windows OpenSSH agent named
    pipe \\.\pipe\openssh-ssh-agent, or $env:SSH_AUTH_SOCK when set).

    Messages used (PROTOCOL.agent):
      SSH2_AGENTC_REQUEST_IDENTITIES (11) -> SSH2_AGENT_IDENTITIES_ANSWER (12)
      SSH2_AGENTC_SIGN_REQUEST      (13) -> SSH2_AGENT_SIGN_RESPONSE   (14)

    Signatures are verified locally afterwards (.NET for RSA/ECDSA,
    ssh-keygen SSHSIG envelope for Ed25519) to prove the agent really
    signed the data with the private key.

    The agent socket defaults to $env:SSH_AUTH_SOCK when set, otherwise to
    the Windows OpenSSH agent pipe \\.\pipe\openssh-ssh-agent. Use
    -Socket to override, -ListSockets to enumerate candidates, or -Choose
    to pick interactively.

.PARAMETER Socket
    Agent socket to use: a bare pipe name (e.g. openssh-ssh-agent), a full
    named-pipe path (\\.\pipe\openssh-ssh-agent), or a Unix socket path
    (pwsh 7 / .NET Core 3+ only). Defaults to $env:SSH_AUTH_SOCK if set,
    else \\.\pipe\openssh-ssh-agent.
.PARAMETER ListSockets
    List candidate agent sockets and exit.
.PARAMETER Choose
    Select the agent socket interactively from the discovered candidates.
#>
[CmdletBinding()]
param(
    [string]$Message = "NotiSync agent-sign demo @ $(Get-Date -Format o)",
    [string]$DataFile = '',
    [string]$Namespace = 'notisync-agent-demo',

    # socket selection
    [string]$Socket = '',
    [switch]$ListSockets,
    [switch]$Choose
)

$ErrorActionPreference = 'Stop'
$DefaultSocket = '\\.\pipe\openssh-ssh-agent'

# SSH agent protocol constants
$AGENT_FAILURE           = 5
$AGENT_REQUEST_IDENT     = 11
$AGENT_IDENTITIES_ANSWER = 12
$AGENT_SIGN_REQUEST      = 13
$AGENT_SIGN_RESPONSE     = 14
$AGENT_RSA_SHA2_256      = 2

# ---------- low-level wire helpers (all ints big-endian; strings = uint32 len + bytes) ----------
function ConvertTo-BE32 {
    param([uint32]$Value)
    $b = [BitConverter]::GetBytes($Value)
    [Array]::Reverse($b)
    return ,$b
}

function ConvertFrom-BE32 {
    param([byte[]]$Bytes)
    $c = $Bytes.Clone()
    [Array]::Reverse($c)
    return [BitConverter]::ToUInt32($c, 0)
}

function New-StringPart {
    param([byte[]]$Data)
    $out = New-Object byte[] ($Data.Length + 4)
    [Array]::Copy((ConvertTo-BE32 ([uint32]$Data.Length)), 0, $out, 0, 4)
    [Array]::Copy($Data, 0, $out, 4, $Data.Length)
    return ,$out
}

function New-AgentMessage {
    param([byte]$Type, [byte[][]]$Parts)
    $bodyLen = 1
    foreach ($p in $Parts) { $bodyLen += $p.Length }
    $body = New-Object byte[] $bodyLen
    $body[0] = $Type
    $off = 1
    foreach ($p in $Parts) {
        [Array]::Copy($p, 0, $body, $off, $p.Length)
        $off += $p.Length
    }
    $msg = New-Object byte[] ($bodyLen + 4)
    [Array]::Copy((ConvertTo-BE32 ([uint32]$bodyLen)), 0, $msg, 0, 4)
    [Array]::Copy($body, 0, $msg, 4, $bodyLen)
    return ,$msg
}

function Read-Exact {
    param([System.IO.Stream]$Stream, [int]$Count)
    $buf = New-Object byte[] $Count
    $off = 0
    while ($off -lt $Count) {
        $n = $Stream.Read($buf, $off, $Count - $off)
        if ($n -le 0) { throw 'Agent stream closed unexpectedly.' }
        $off += $n
    }
    return ,$buf
}

function Read-AgentMessage {
    param([System.IO.Stream]$Stream)
    $lenBytes = Read-Exact $Stream 4
    $len = ConvertFrom-BE32 $lenBytes
    if ($len -gt 1MB) { throw "Agent response too large ($len bytes)." }
    return ,(Read-Exact $Stream $len)
}

function Read-StringPart {
    param([byte[]]$Data, [ref]$Pos)
    $len = ConvertFrom-BE32 ($Data[$Pos.Value..($Pos.Value + 3)])
    $Pos.Value += 4
    if ($len -gt ($Data.Length - $Pos.Value)) { throw "Malformed agent data: string length $len exceeds remaining $($Data.Length - $Pos.Value) bytes at offset $($Pos.Value - 4)." }
    if ($len -eq 0) { return ,([byte[]]@()) }
    $s = $Data[$Pos.Value..($Pos.Value + $len - 1)]
    $Pos.Value += $len
    return ,$s
}

function Read-Mpint {
    param([byte[]]$Data, [ref]$Pos)
    $len = ConvertFrom-BE32 ($Data[$Pos.Value..($Pos.Value + 3)])
    $Pos.Value += 4
    if ($len -eq 0) { return ,([byte[]]@()) }
    $v = $Data[$Pos.Value..($Pos.Value + $len - 1)]
    $Pos.Value += $len
    return ,$v
}

function Remove-LeadingZeros {
    param([byte[]]$Bytes)
    $i = 0
    while ($i -lt $Bytes.Length - 1 -and $Bytes[$i] -eq 0) { $i++ }
    return ,$Bytes[$i..($Bytes.Length - 1)]
}

# ---------- socket helpers ----------
function Resolve-AgentSocket {
    param([string]$Socket)
    if ([string]::IsNullOrWhiteSpace($Socket)) { return $null }
    if ($Socket -like '\\.\pipe\*') { return $Socket }                              # full named-pipe path
    if ($Socket -match '^[A-Za-z]:[\\/]' -or $Socket -match '^/') { return $Socket } # filesystem path -> Unix socket
    if ($Socket -match '[\\/]') { return $Socket }
    return '\\.\pipe\' + $Socket                                                     # bare pipe name
}

function New-AgentStream {
    param([string]$Socket)
    if ($Socket -like '\\.\pipe\*') {
        $name = $Socket.Substring(8)
        $client = New-Object System.IO.Pipes.NamedPipeClientStream('.', $name, [System.IO.Pipes.PipeDirection]::InOut)
        $client.Connect(5000)
        return $client
    }
    # not a named pipe: try a Unix domain socket (pwsh 7 / .NET Core 3+ only)
    try {
        $ep = New-Object System.Net.Sockets.UnixDomainSocketEndPoint($Socket)
    } catch {
        throw "Cannot connect to socket '$Socket': not a Windows named pipe, and Unix domain sockets require pwsh 7 (.NET Core 3+)."
    }
    $sock = New-Object System.Net.Sockets.Socket([System.Net.Sockets.AddressFamily]::Unix, [System.Net.Sockets.SocketType]::Stream, [System.Net.Sockets.ProtocolType]::IP)
    $sock.Connect($ep)
    $ns = New-Object System.Net.Sockets.NetworkStream($sock, $true)
    $ns.ReadTimeout = 5000
    $ns.WriteTimeout = 5000
    return $ns
}

function Get-AgentSocketCandidates {
    $pipes = @()
    try { $pipes = [IO.Directory]::GetFiles('\\.\pipe\') } catch { }
    $cands = @($pipes | Where-Object { $_ -match 'ssh|agent' } | Sort-Object)
    if ($env:SSH_AUTH_SOCK) {
        $envSock = Resolve-AgentSocket $env:SSH_AUTH_SOCK
        if ($envSock -and ($cands -notcontains $envSock)) { $cands += $envSock }
    }
    return $cands   # no comma-wrap: callers use @(...) which needs the elements, not a nested array
}

# ---------- agent operations ----------
function Invoke-AgentSign {
    param([System.IO.Stream]$Stream, [byte[]]$KeyBlob, [byte[]]$Data, [uint32]$Flags)
    $parts = @((New-StringPart $KeyBlob), (New-StringPart $Data), (ConvertTo-BE32 $Flags))
    $req = New-AgentMessage $AGENT_SIGN_REQUEST $parts
    $Stream.Write($req, 0, $req.Length)
    $resp = Read-AgentMessage $Stream
    if ($resp[0] -ne $AGENT_SIGN_RESPONSE) {
        if ($resp[0] -eq $AGENT_FAILURE) { throw 'Agent refused the sign request (SSH_AGENT_FAILURE).' }
        throw "Expected SSH2_AGENT_SIGN_RESPONSE (14), got $($resp[0])."
    }
    $pos = 1
    return ,(Read-StringPart $resp ([ref]$pos))   # signature blob: string alg, string sig
}

function Parse-SignatureBlob {
    param([byte[]]$Blob)
    $pos = 0
    $format = [Text.Encoding]::ASCII.GetString((Read-StringPart $Blob ([ref]$pos)))
    $sig = Read-StringPart $Blob ([ref]$pos)
    return [pscustomobject]@{ Format = $format; Signature = $sig }
}

function Test-RsaSignature {
    param([byte[]]$KeyBlob, [byte[]]$Signature, [byte[]]$Data, [string]$Format)
    $pos = 0
    $null = Read-StringPart $KeyBlob ([ref]$pos)          # 'ssh-rsa'
    $e = Remove-LeadingZeros (Read-Mpint $KeyBlob ([ref]$pos))
    $n = Remove-LeadingZeros (Read-Mpint $KeyBlob ([ref]$pos))
    $rsa = [Security.Cryptography.RSA]::Create()
    try {
        $p = New-Object Security.Cryptography.RSAParameters
        $p.Exponent = $e
        $p.Modulus = $n
        $rsa.ImportParameters($p)
        switch ($Format) {
            'rsa-sha2-256' { return $rsa.VerifyData($Data, $Signature, [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pkcs1) }
            'rsa-sha2-512' { return $rsa.VerifyData($Data, $Signature, [Security.Cryptography.HashAlgorithmName]::SHA512, [Security.Cryptography.RSASignaturePadding]::Pkcs1) }
            default { return "unverified (unsupported RSA format: $Format)" }
        }
    } finally { $rsa.Dispose() }
}

function Get-ECCurve {
    param([string]$Name)
    $oidValue = switch ($Name) {
        'nistp256' { '1.2.840.10045.3.1.7' }    # prime256v1 / secp256r1
        'nistp384' { '1.3.132.0.34' }           # secp384r1
        'nistp521' { '1.3.132.0.35' }           # secp521r1
        default { throw "Unsupported curve: $Name" }
    }
    $oid = New-Object System.Security.Cryptography.Oid($oidValue)
    return [System.Security.Cryptography.ECCurve]::CreateFromOid($oid)
}

function Test-EcdsaSignature {
    param([byte[]]$KeyBlob, [byte[]]$Signature, [byte[]]$Data)
    $pos = 0
    $null = Read-StringPart $KeyBlob ([ref]$pos)          # 'ecdsa-sha2-nistp256'
    $curveName = [Text.Encoding]::ASCII.GetString((Read-StringPart $KeyBlob ([ref]$pos)))
    $q = Read-StringPart $KeyBlob ([ref]$pos)             # 0x04 || X || Y
    if ($q[0] -ne 4) { return "unverified (point encoding 0x$('{0:x2}' -f $q[0]))" }
    $half = ($q.Length - 1) / 2
    $x = Remove-LeadingZeros $q[1..$half]
    $y = Remove-LeadingZeros $q[($half + 1)..($q.Length - 1)]
    # .NET requires Q.X and Q.Y to be the same length; re-pad to a common size
    $coordLen = [Math]::Max($x.Length, $y.Length)
    if ($x.Length -lt $coordLen) { $x = [byte[]]((New-Object byte[] ($coordLen - $x.Length)) + $x) }
    if ($y.Length -lt $coordLen) { $y = [byte[]]((New-Object byte[] ($coordLen - $y.Length)) + $y) }
    $ecdsa = [Security.Cryptography.ECDsa]::Create()
    try {
        $ec = New-Object Security.Cryptography.ECParameters
        $ec.Curve = Get-ECCurve $curveName
        $point = New-Object Security.Cryptography.ECPoint
        $point.X = $x
        $point.Y = $y
        $ec.Q = $point       # assign the whole struct; $ec.Q.X = ... would set a copy
        $ecdsa.ImportParameters($ec)
        # agent encodes the ECDSA signature as string r + string s (mpints);
        # .NET wants raw r||s concatenated without length prefixes/sign bytes
        $spos = 0
        $r = Remove-LeadingZeros (Read-StringPart $Signature ([ref]$spos))
        $s = Remove-LeadingZeros (Read-StringPart $Signature ([ref]$spos))
        $sigLen = [Math]::Max($r.Length, $s.Length)
        if ($r.Length -lt $sigLen) { $r = [byte[]]((New-Object byte[] ($sigLen - $r.Length)) + $r) }
        if ($s.Length -lt $sigLen) { $s = [byte[]]((New-Object byte[] ($sigLen - $s.Length)) + $s) }
        $rawSig = [byte[]]($r + $s)
        $hashName = switch ($curveName) {
            'nistp256' { [Security.Cryptography.HashAlgorithmName]::SHA256 }
            'nistp384' { [Security.Cryptography.HashAlgorithmName]::SHA384 }
            'nistp521' { [Security.Cryptography.HashAlgorithmName]::SHA512 }
        }
        return $ecdsa.VerifyData($Data, $rawSig, $hashName)
    } finally { $ecdsa.Dispose() }
}

function Build-SshSigEnvelope {
    param([byte[]]$KeyBlob, [byte[]]$SignatureBlob, [string]$Namespace, [byte[]]$Data)
    $sha = [Security.Cryptography.SHA256]::Create()
    $hash = $sha.ComputeHash($Data)
    $parts = @(
        (New-StringPart ([Text.Encoding]::ASCII.GetBytes('SSHSIG'))),
        (New-StringPart ([Text.Encoding]::ASCII.GetBytes('1'))),
        (New-StringPart $KeyBlob),
        (New-StringPart ([Text.Encoding]::ASCII.GetBytes($Namespace))),
        (New-StringPart ([byte[]]@())),                                   # reserved
        (New-StringPart ([Text.Encoding]::ASCII.GetBytes('sha256'))),
        (New-StringPart $hash),
        (New-StringPart $SignatureBlob)
    )
    $total = 0
    foreach ($p in $parts) { $total += $p.Length }
    $env = New-Object byte[] $total
    $off = 0
    foreach ($p in $parts) {
        [Array]::Copy($p, 0, $env, $off, $p.Length)
        $off += $p.Length
    }
    return ,$env
}

function Test-Ed25519WithSshKeygen {
    param([byte[]]$KeyBlob, [byte[]]$SignatureBlob, [byte[]]$Data, [string]$Namespace, [string]$KeyType, [string]$Principal)
    if (-not (Get-Command ssh-keygen -ErrorAction SilentlyContinue)) { return 'skipped (ssh-keygen not on PATH)' }
    $tmp = Join-Path $env:TEMP ('agent-demo-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $tmp | Out-Null
    try {
        $dataPath = Join-Path $tmp 'data.bin'
        $sigPath = Join-Path $tmp 'sig.sig'
        $allowedPath = Join-Path $tmp 'allowed_signers'
        [IO.File]::WriteAllBytes($dataPath, $Data)
        [IO.File]::WriteAllBytes($sigPath, (Build-SshSigEnvelope $KeyBlob $SignatureBlob $Namespace $Data))
        Set-Content -LiteralPath $allowedPath -Value "$Principal $KeyType $([Convert]::ToBase64String($KeyBlob))" -Encoding ascii
        $out = & ssh-keygen -Y verify -f $allowedPath -I $Principal -n $Namespace -s $sigPath $dataPath 2>&1
        if ($LASTEXITCODE -eq 0) { return 'True (ssh-keygen SSHSIG)' }
        return "False ($($out -join ' '))"
    } finally {
        Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    }
}

# ---------- main ----------
Write-Host ''
Write-Host '=== SSH agent signing demo ===' -ForegroundColor Cyan

# --- socket selection ---
if ($ListSockets) {
    Write-Host 'Available agent sockets:'
    $cands = @(Get-AgentSocketCandidates)
    if ($cands.Count -eq 0) { Write-Host '  (no ssh/agent named pipes found)' }
    else { $cands | ForEach-Object { Write-Host ("  " + $_) } }
    if ($env:SSH_AUTH_SOCK) { Write-Host ("SSH_AUTH_SOCK : " + $env:SSH_AUTH_SOCK) }
    exit 0
}
if ($Choose) {
    $cands = @(Get-AgentSocketCandidates)
    if ($cands.Count -eq 0) {
        Write-Host 'No agent sockets found to choose from.' -ForegroundColor Yellow
        exit 1
    }
    for ($i = 0; $i -lt $cands.Count; $i++) { Write-Host ("  [{0}] {1}" -f ($i + 1), $cands[$i]) }
    $sel = Read-Host 'Select a socket (number)'
    $idx = 0
    if (-not [int]::TryParse($sel, [ref]$idx) -or $idx -lt 1 -or $idx -gt $cands.Count) {
        Write-Host 'Invalid selection.' -ForegroundColor Red
        exit 1
    }
    $socketPath = $cands[$idx - 1]
} elseif ($Socket) {
    $socketPath = Resolve-AgentSocket $Socket
} elseif ($env:SSH_AUTH_SOCK) {
    $socketPath = Resolve-AgentSocket $env:SSH_AUTH_SOCK
} else {
    $socketPath = $DefaultSocket
}

Write-Host ("Agent socket : {0}" -f $socketPath)

$svc = Get-Service -Name ssh-agent -ErrorAction SilentlyContinue
if ($svc) { Write-Host ("Service     : OpenSSH Authentication Agent ({0})" -f $svc.Status) }
else { Write-Host 'Service     : ssh-agent service not found' }

if ($socketPath -like '\\.\pipe\*') {
    try {
        $pipes = [IO.Directory]::GetFiles('\\.\pipe\')
        Write-Host ("Pipe seen   : {0}" -f ($pipes -contains $socketPath))
    } catch {
        Write-Host ("Pipe check  : n/a ({0})" -f $_.Exception.Message)
    }
}

if ($DataFile) {
    $dataPath = (Resolve-Path $DataFile).Path
    $dataBytes = [IO.File]::ReadAllBytes($dataPath)
    $what = "file: $dataPath ($($dataBytes.Length) bytes)"
} else {
    $dataBytes = [Text.Encoding]::UTF8.GetBytes($Message)
    $what = "message: $Message"
}

$digest = [Security.Cryptography.SHA256]::Create().ComputeHash($dataBytes)
Write-Host ''
Write-Host ("Data to sign : {0}" -f $what)
Write-Host ("SHA-256      : {0}" -f (($digest | ForEach-Object { $_.ToString('x2') }) -join ''))

$client = New-AgentStream $socketPath
try {
    # 1) list identities
    $req = New-AgentMessage $AGENT_REQUEST_IDENT @()
    $client.Write($req, 0, $req.Length)
    $resp = Read-AgentMessage $client
    if ($resp[0] -ne $AGENT_IDENTITIES_ANSWER) { throw "Expected SSH2_AGENT_IDENTITIES_ANSWER (12), got $($resp[0])." }

    $pos = 1
    $numKeys = ConvertFrom-BE32 $resp[1..4]
    $pos = 5   # advance past the 4-byte key-count field
    Write-Host ''
    Write-Host ("Keys in agent: {0}" -f $numKeys)

    if ($numKeys -eq 0) {
        Write-Host ''
        Write-Host 'No keys loaded. Add one with:  ssh-add <path-to-private-key>' -ForegroundColor Yellow
        exit 0
    }

    $keys = @()
    for ($i = 0; $i -lt $numKeys; $i++) {
        $blob = Read-StringPart $resp ([ref]$pos)
        $commentBytes = Read-StringPart $resp ([ref]$pos)
        $kpos = 0
        $keyType = [Text.Encoding]::ASCII.GetString((Read-StringPart $blob ([ref]$kpos)))
        $comment = [Text.Encoding]::UTF8.GetString($commentBytes)
        $fpBytes = [Security.Cryptography.SHA256]::Create().ComputeHash($blob)
        $fp = 'SHA256:' + ([Convert]::ToBase64String($fpBytes) -replace '=+$', '' -replace '\+', '-' -replace '/', '_')
        $keys += [pscustomobject]@{ Blob = $blob; Type = $keyType; Comment = $comment; Fingerprint = $fp }
        Write-Host ''
        Write-Host ("[{0}] {1}  {2}  ({3})" -f ($i + 1), $keyType, $fp, $comment)
    }

    # 2) sign with every key
    $i = 0
    $allOk = $true
    foreach ($key in $keys) {
        $i++
        Write-Host ''
        Write-Host ("--- Signing with key #{0} ({1}) ---" -f $i, $key.Type)
        switch ($key.Type) {
            'ssh-rsa' {
                # flag 2 forces rsa-sha2-256: agent signs DigestInfo(SHA256(data)) with PKCS#1 v1.5
                $sigBlob = Invoke-AgentSign $client $key.Blob $dataBytes $AGENT_RSA_SHA2_256
                $parsed = Parse-SignatureBlob $sigBlob
                $verdict = Test-RsaSignature $key.Blob $parsed.Signature $dataBytes $parsed.Format
            }
            { $_ -like 'ecdsa-sha2-*' } {
                # agent hashes data with SHA-256/384/512 per curve before signing
                $sigBlob = Invoke-AgentSign $client $key.Blob $dataBytes 0
                $parsed = Parse-SignatureBlob $sigBlob
                $verdict = Test-EcdsaSignature $key.Blob $parsed.Signature $dataBytes
            }
            'ssh-ed25519' {
                # agent signs exactly the bytes given; sign the SHA-256 digest so the
                # result fits the SSHSIG envelope that ssh-keygen -Y verify expects
                $sigBlob = Invoke-AgentSign $client $key.Blob $digest 0
                $parsed = Parse-SignatureBlob $sigBlob
                $principal = $key.Comment -replace '\s+', '_'
                if (-not $principal) { $principal = 'agent-demo' }
                $verdict = Test-Ed25519WithSshKeygen $key.Blob $sigBlob $dataBytes $Namespace $key.Type $principal
            }
            default {
                $sigBlob = Invoke-AgentSign $client $key.Blob $dataBytes 0
                $parsed = Parse-SignatureBlob $sigBlob
                $verdict = 'n/a (no verifier for this key type)'
            }
        }
        Write-Host ("  signature format : {0}" -f $parsed.Format)
        Write-Host ("  signature (b64)  : {0}" -f [Convert]::ToBase64String($parsed.Signature))
        Write-Host ("  verified         : {0}" -f $verdict)
        if ($verdict -isnot [bool] -or -not $verdict) { $allOk = $false }
    }

    Write-Host ''
    if ($allOk) { Write-Host 'All signatures verified OK.' -ForegroundColor Green }
    else { Write-Host 'Some signatures could not be verified (see above).' -ForegroundColor Yellow }
}
catch {
    Write-Host ''
    Write-Host ("FAILED: " + $_.Exception.Message) -ForegroundColor Red
    if ($_.Exception.InnerException) { Write-Host ("  -> " + $_.Exception.InnerException.Message) -ForegroundColor DarkRed }
    if ($_.Exception -is [System.TimeoutException]) {
        Write-Host ''
        Write-Host ("No agent responded on {0}." -f $socketPath)
        Write-Host 'Start one with:  ssh-agent  (user session)  or  Start-Service ssh-agent  (admin),'
        Write-Host 'then load keys with:  ssh-add <path-to-private-key>'
    }
    exit 1
}
finally {
    if ($client) { $client.Dispose() }
}
