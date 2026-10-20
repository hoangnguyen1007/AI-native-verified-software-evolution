# Trusted driver contract test: mocks Hyper-V cmdlets; creates no VM, switch or virtual disk.
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$testRoot=Join-Path ([IO.Path]::GetTempPath()) ('evo-driver-test-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
$global:driverTestVm=$null
$global:driverTestDisk=$null
$global:driverTestBase=Join-Path $testRoot 'base.vhdx'
[IO.File]::WriteAllBytes($global:driverTestBase,[byte[]]@(1))
$global:driverTestMutations=0
$global:driverTestBaseAttached=$false
$global:driverTestDiskNumber=$null
function Get-VHD {param($Path) [pscustomobject]@{Attached=$(if($Path -eq $global:driverTestBase){$global:driverTestBaseAttached}else{$false});DiskNumber=$global:driverTestDiskNumber;ParentPath=$(if($Path -eq $global:driverTestBase){''}else{$global:driverTestBase})}}
function New-VHD {param($Path,$ParentPath,[switch]$Differencing) [IO.File]::WriteAllBytes($Path,[byte[]]@(2));$global:driverTestDisk=$Path}
function Get-VM {param($Name,$Id,$ErrorAction) $global:driverTestVm}
function New-VM {param($Name,$Generation,$MemoryStartupBytes,$VHDPath,$Path)
 $global:driverTestVm=[pscustomobject]@{Name=$Name;Id=[Guid]::NewGuid();State='Off';ProcessorCount=2;MemoryStartup=$MemoryStartupBytes}
 $global:driverTestVm
}
function Get-VMHardDiskDrive {param($VM) [pscustomobject]@{Path=$global:driverTestDisk}}
function Set-VM {param($VM,$AutomaticCheckpointsEnabled,$AutomaticStartAction,$AutomaticStopAction) $global:driverTestMutations++}
function Set-VMProcessor {param($VM,$Count,$Maximum) if($VM.State -ne 'Off'){throw 'LIVE_WORKER_RECONFIGURATION'};$global:driverTestMutations++}
function Set-VMMemory {param($VM,$DynamicMemoryEnabled,$StartupBytes) if($VM.State -ne 'Off'){throw 'LIVE_WORKER_RECONFIGURATION'};$global:driverTestMutations++}
function Set-VMFirmware {param($VM,$EnableSecureBoot) if($VM.State -ne 'Off'){throw 'LIVE_WORKER_RECONFIGURATION'};$global:driverTestMutations++}
$driver=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../main/resources/hyperv/worker.ps1'))
$attempt=[Guid]::NewGuid()
# First creation persists ownership before resource creation, then publishes the VM identity.
$first=(& $driver -Action Create -Workspace $testRoot -Attempt $attempt -ExportedDisk $global:driverTestBase) | ConvertFrom-Json
if($first.state -ne 'Off'){throw 'FIRST_CREATE_FAILED'}
$global:driverTestVm.State='Running'
$global:driverTestBaseAttached=$true
$before=$global:driverTestMutations
$second=(& $driver -Action Create -Workspace $testRoot -Attempt $attempt -ExportedDisk $global:driverTestBase) | ConvertFrom-Json
if($second.vmId -ne $first.vmId -or $second.state -ne 'Running' -or $before -ne $global:driverTestMutations){throw 'CREATE_RETRY_MUTATED_RUNNING_WORKER'}
function Expect-Reason([scriptblock]$Operation,[string]$Reason) {
    try{& $Operation | Out-Null;throw 'EXPECTED_REJECTION_MISSING'}catch{if($_.Exception.Message -ne $Reason){throw}}
}
Expect-Reason {& $driver -Action Create -Workspace $testRoot -Attempt $attempt -ExportedDisk $global:driverTestBase -Processors 3} 'WORKER_RESOURCE_PROFILE_MISMATCH'
$oldId=$global:driverTestVm.Id
$global:driverTestVm.Id=[Guid]::NewGuid()
Expect-Reason {& $driver -Action Create -Workspace $testRoot -Attempt $attempt -ExportedDisk $global:driverTestBase} 'WORKER_RECOVERY_ID_MISMATCH'
$global:driverTestVm.Id=$oldId
$savedDisk=$global:driverTestDisk
$global:driverTestDisk=$global:driverTestBase
Expect-Reason {& $driver -Action Create -Workspace $testRoot -Attempt $attempt -ExportedDisk $global:driverTestBase} 'IMMUTABLE_BASE_DIRECTLY_ATTACHED'
$alias=Join-Path $testRoot 'base-alias.vhdx'
New-Item -ItemType HardLink -Path $alias -Target $global:driverTestBase | Out-Null
$global:driverTestDisk=$alias
Expect-Reason {& $driver -Action Create -Workspace $testRoot -Attempt $attempt -ExportedDisk $global:driverTestBase} 'IMMUTABLE_BASE_DIRECTLY_ATTACHED'
$global:driverTestDisk=$savedDisk
$global:driverTestDiskNumber=7
Expect-Reason {& $driver -Action Create -Workspace $testRoot -Attempt $attempt -ExportedDisk $global:driverTestBase} 'IMMUTABLE_BASE_HOST_MOUNTED'
$global:driverTestDiskNumber=$null
$savedVm=$global:driverTestVm
$global:driverTestVm=$null
Expect-Reason {& $driver -Action Create -Workspace $testRoot -Attempt $attempt -ExportedDisk $global:driverTestBase} 'IMMUTABLE_BASE_ATTACHMENT_UNVERIFIED'
$global:driverTestVm=$savedVm
$marker=Join-Path $testRoot ('owner-'+$attempt.ToString('N')+'.json')
$launch='sha256:'+('a'*64)
Expect-Reason {& $driver -Action Find -Workspace $testRoot -Attempt $attempt -LaunchDigest $launch -Fence 1} 'WORKER_LAUNCH_BINDING_MISMATCH'
Expect-Reason {& $driver -Action Bind -Workspace $testRoot -Attempt $attempt -LaunchDigest $launch -Fence 1} 'WORKER_BIND_REQUIRES_OFF'
$global:driverTestVm.State='Off'
$bound=(& $driver -Action Bind -Workspace $testRoot -Attempt $attempt -LaunchDigest $launch -Fence 1) | ConvertFrom-Json
if($bound.launch -ne $launch -or $bound.fence -ne 1){throw 'BINDING_NOT_PUBLISHED'}
$found=(& $driver -Action Find -Workspace $testRoot -Attempt $attempt -LaunchDigest $launch -Fence 1) | ConvertFrom-Json
if($found.vmId -ne $oldId.ToString()){throw 'EXACT_RECOVERY_FAILED'}
Expect-Reason {& $driver -Action Find -Workspace $testRoot -Attempt $attempt -LaunchDigest $launch -Fence 2} 'WORKER_LAUNCH_BINDING_MISMATCH'
Expect-Reason {& $driver -Action Bind -Workspace $testRoot -Attempt $attempt -LaunchDigest ('sha256:'+('b'*64)) -Fence 1} 'WORKER_LAUNCH_BINDING_CONFLICT'
$absent=(& $driver -Action Find -Workspace $testRoot -Attempt ([Guid]::NewGuid()) -LaunchDigest $launch -Fence 1) | ConvertFrom-Json
if($absent.state -ne 'Absent'){throw 'ABSENCE_NOT_RECORDED'}
$savedGetVm=${function:Get-VM}
function Get-VM {throw 'HOST_UNAVAILABLE'}
Expect-Reason {& $driver -Action Find -Workspace $testRoot -Attempt ([Guid]::NewGuid()) -LaunchDigest $launch -Fence 1} 'HOST_UNAVAILABLE'
${function:Get-VM}=$savedGetVm
$before=$global:driverTestMutations
& $driver -Action Stop -Workspace $testRoot -Attempt $attempt -LaunchDigest $launch -Fence 1 | Out-Null
if($before -ne $global:driverTestMutations){throw 'OFF_STOP_MUTATED_WORKER'}
$validMarker=Get-Content -LiteralPath $marker -Raw
$malformed=$validMarker | ConvertFrom-Json
$malformed.launch=@($launch)
$malformed | ConvertTo-Json -Compress | Set-Content -LiteralPath $marker -Encoding utf8
Expect-Reason {& $driver -Action Find -Workspace $testRoot -Attempt $attempt -LaunchDigest $launch -Fence 1} 'INVALID_WORKER_LAUNCH_BINDING'
$malformed=$validMarker | ConvertFrom-Json
$malformed.fence=1.5
$malformed | ConvertTo-Json -Compress | Set-Content -LiteralPath $marker -Encoding utf8
Expect-Reason {& $driver -Action Find -Workspace $testRoot -Attempt $attempt -LaunchDigest $launch -Fence 1} 'INVALID_WORKER_LAUNCH_BINDING'
[IO.File]::WriteAllText($marker,$validMarker)
$owner=Get-Content -LiteralPath $marker -Raw | ConvertFrom-Json
$owner.workspace=Join-Path $testRoot 'foreign'
$owner | ConvertTo-Json -Compress | Set-Content -LiteralPath $marker -Encoding utf8
Expect-Reason {& $driver -Action Inspect -Workspace $testRoot -Attempt $attempt} 'WORKER_OWNERSHIP_MISMATCH'
$emptyBase=New-Item -ItemType Directory -Path (Join-Path $testRoot 'different-base-root')
Expect-Reason {& $driver -Action Create -Workspace $testRoot -BaseRoot $emptyBase.FullName -Attempt ([Guid]::NewGuid()) -ExportedDisk $global:driverTestBase} 'PATH_OUTSIDE_WORKSPACE'
$link=New-Item -ItemType Junction -Path (Join-Path $testRoot 'alias') -Target $emptyBase.FullName
Expect-Reason {& $driver -Action Inspect -Workspace $link.FullName -Attempt $attempt} 'WORKSPACE_REPARSE_POINT'
'PASS 21 driver controls: original 10 plus missing binding, live bind refusal, exact binding, exact recovery, stale fence, conflicting binding, authoritative absence, unavailable host, idempotent Off stop, array binding, fractional fence'

# Retain the tiny test directory for failure inspection; no recursive cleanup is performed.
'RUNTIME '+$testRoot
