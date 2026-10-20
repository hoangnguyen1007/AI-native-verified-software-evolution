# Real Windows file sharing and independent PowerShell processes; Hyper-V calls are mocked.
# No target command, VM or disk is created. Retain the tiny scratch directory for diagnosis.
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$root=Join-Path ([IO.Path]::GetTempPath()) ('evo-lifecycle-test-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
$driver=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../main/resources/hyperv/worker.ps1'))
$attempt=[Guid]::NewGuid();$launch='sha256:'+('a'*64);$vmId=[Guid]::NewGuid()
$disk=Join-Path $root 'private.vhdx';[IO.File]::WriteAllBytes($disk,[byte[]]@(0))
$owner=[ordered]@{schema='hyperv-worker-owner:m8.4-v1';attempt=$attempt.ToString();name=('evo-worker-'+$attempt.ToString('N'));vmId=$vmId.ToString();disk=$disk;workspace=$root;phase='CREATED';launch=$launch;fence=1}
$owner | ConvertTo-Json -Compress | Set-Content -LiteralPath (Join-Path $root ('owner-'+$attempt.ToString('N')+'.json')) -Encoding utf8
[IO.File]::WriteAllText((Join-Path $root 'state.txt'),'Off')
$operation={
    param($Driver,$Root,$Attempt,$Launch,$Action)
    $ErrorActionPreference='Stop'
    Set-StrictMode -Version Latest
    $global:fixtureRoot=$Root
    $global:fixtureAttempt=[Guid]$Attempt
    function Get-VM {
        param($Id,$ErrorAction)
        $o=Get-Content -LiteralPath (Join-Path $global:fixtureRoot ('owner-'+$global:fixtureAttempt.ToString('N')+'.json')) -Raw | ConvertFrom-Json
        [pscustomobject]@{Name=$o.name;Id=[Guid]$o.vmId;State=[IO.File]::ReadAllText((Join-Path $global:fixtureRoot 'state.txt'));ProcessorCount=2;MemoryStartup=2147483648L}
    }
    function Get-VMHardDiskDrive {param($VM) [pscustomobject]@{Path=(Join-Path $global:fixtureRoot 'private.vhdx')}}
    function Start-VM {
        param($VM)
        [IO.File]::WriteAllText((Join-Path $global:fixtureRoot 'start-entered.txt'),'entered')
        $wait=[Diagnostics.Stopwatch]::StartNew()
        while(-not (Test-Path -LiteralPath (Join-Path $global:fixtureRoot 'release-start.txt'))){
            if($wait.ElapsedMilliseconds -ge 10000){throw 'FIXTURE_START_BARRIER_TIMEOUT'}
            Start-Sleep -Milliseconds 10
        }
        [IO.File]::WriteAllText((Join-Path $global:fixtureRoot 'state.txt'),'Running')
    }
    function Stop-VM {
        param($VM,[switch]$TurnOff,$Confirm)
        $failure=Join-Path $global:fixtureRoot 'fail-stop.txt'
        if((Test-Path -LiteralPath $failure) -and [IO.File]::ReadAllText($failure) -eq 'fail'){throw 'FIXTURE_STOP_FAILED'}
        [IO.File]::WriteAllText((Join-Path $global:fixtureRoot 'state.txt'),'Off')
    }
    & $Driver -Action $Action -Workspace $Root -Attempt $Attempt -LaunchDigest $Launch -Fence 1
}
function Wait-File([string]$Path){
    $wait=[Diagnostics.Stopwatch]::StartNew()
    while(-not (Test-Path -LiteralPath $Path)){
        if($wait.ElapsedMilliseconds -ge 10000){throw 'FIXTURE_BARRIER_TIMEOUT'}
        Start-Sleep -Milliseconds 10
    }
}
function Expect-Reason([scriptblock]$Check,[string]$Reason){
    try{& $Check | Out-Null;throw 'EXPECTED_REJECTION_MISSING'}catch{if($_.Exception.Message -ne $Reason){throw}}
}
# Initialize the driver's trusted type before running independent invocations concurrently.
& $operation $driver $root $attempt $launch 'Find' | Out-Null
function Start-Operation([string]$Action){
    $configuration=@{driver=$driver;root=$root;attempt=$attempt.ToString();launch=$launch;action=$Action} | ConvertTo-Json -Compress
    $encodedArguments=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($configuration))
    $source='$taskArgs=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('''+$encodedArguments+''')) | ConvertFrom-Json; & {'+$operation.ToString()+'} $taskArgs.driver $taskArgs.root $taskArgs.attempt $taskArgs.launch $taskArgs.action'
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($source))
    Start-Process -FilePath (Get-Process -Id $PID).Path -ArgumentList '-NoProfile','-NonInteractive','-EncodedCommand',$encoded -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $root ($Action+'.out')) -RedirectStandardError (Join-Path $root ($Action+'.err'))
}
$starting=Start-Operation 'Start'
$stopping=$null
try{
    Wait-File (Join-Path $root 'start-entered.txt')
    $stopping=Start-Operation 'Stop'
    Wait-File (Join-Path $root ('stop-'+$attempt.ToString('N')+'.json'))
    if($stopping.HasExited){throw 'STOP_BYPASSED_INFLIGHT_START_LOCK'}
    [IO.File]::WriteAllText((Join-Path $root 'release-start.txt'),'release')
    if(-not $starting.WaitForExit(15000) -or -not $stopping.WaitForExit(15000)){throw 'FIXTURE_CHILD_TIMEOUT'}
    if($starting.ExitCode -ne 0 -or $stopping.ExitCode -ne 0){throw 'FIXTURE_CHILD_FAILED'}
    $started=Get-Content -LiteralPath (Join-Path $root 'Start.out') -Raw | ConvertFrom-Json
    $stopped=Get-Content -LiteralPath (Join-Path $root 'Stop.out') -Raw | ConvertFrom-Json
    if($started.vmId -ne $vmId.ToString() -or $stopped.vmId -ne $vmId.ToString() -or $stopped.state -ne 'Off'){throw 'SERIALIZED_STOP_RESULT_INCORRECT'}
    if([IO.File]::ReadAllText((Join-Path $root 'state.txt')) -ne 'Off'){throw 'LATE_START_SURVIVED_STOP'}
    Expect-Reason {& $operation $driver $root $attempt $launch 'Start'} 'WORKER_ATTEMPT_TERMINAL'
    $retry=(& $operation $driver $root $attempt $launch 'Stop') | ConvertFrom-Json
    if($retry.state -ne 'Off'){throw 'STOP_RETRY_NOT_IDEMPOTENT'}
    # A failed hypervisor stop is not proof; the barrier must still reject any restart.
    [IO.File]::WriteAllText((Join-Path $root 'state.txt'),'Running')
    [IO.File]::WriteAllText((Join-Path $root 'fail-stop.txt'),'fail')
    Expect-Reason {& $operation $driver $root $attempt $launch 'Stop'} 'FIXTURE_STOP_FAILED'
    Expect-Reason {& $operation $driver $root $attempt $launch 'Start'} 'WORKER_ATTEMPT_TERMINAL'
    # A truncated durable barrier is fail-closed, never a new launch authorization.
    [IO.File]::WriteAllText((Join-Path $root ('stop-'+$attempt.ToString('N')+'.json')),'')
    Expect-Reason {& $operation $driver $root $attempt $launch 'Start'} 'WORKER_ATTEMPT_TERMINAL'
    # Even with an unwritable stop record, attempt the hypervisor stop and report unknown.
    [IO.File]::WriteAllText((Join-Path $root 'fail-stop.txt'),'recovered')
    $barrierLock=[IO.File]::Open((Join-Path $root ('stop-'+$attempt.ToString('N')+'.json')),[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    try{Expect-Reason {& $operation $driver $root $attempt $launch 'Stop'} 'WORKER_STOP_INTENT_UNCONFIRMED'}finally{$barrierLock.Dispose()}
    if([IO.File]::ReadAllText((Join-Path $root 'state.txt')) -ne 'Off'){throw 'STOP_STORAGE_FAILURE_LEFT_VM_RUNNING'}
    # A later exact Stop repairs and durably flushes the partial barrier.
    & $operation $driver $root $attempt $launch 'Stop' | Out-Null
    $repaired=Get-Content -LiteralPath (Join-Path $root ('stop-'+$attempt.ToString('N')+'.json')) -Raw | ConvertFrom-Json
    if($repaired.attempt -ne $attempt.ToString() -or $repaired.fence -ne 1){throw 'PARTIAL_BARRIER_RECOVERY_FAILED'}
    'PASS lifecycle interleaving, terminal retry, idempotent Stop, failed Stop, truncated barrier'
    'RUNTIME '+$root
}finally{
    # Release/stop only these freshly created test processes; preserve scratch files.
    if(-not (Test-Path -LiteralPath (Join-Path $root 'release-start.txt'))){[IO.File]::WriteAllText((Join-Path $root 'release-start.txt'),'release')}
    if(-not $starting.HasExited){$starting.Kill($true)}
    if($null -ne $stopping -and -not $stopping.HasExited){$stopping.Kill($true)}
}
