#requires -Version 7.0
param(
    [Parameter(Mandatory=$true)][ValidateSet('Prepare','Start')][string]$Action,
    [Parameter(Mandatory=$true)][string]$Request,
    [Parameter(Mandatory=$true)][ValidatePattern('^sha256:[a-f0-9]{64}$')][string]$RequestPin
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest

function Assert-Fields([object]$Value,[string[]]$Fields){
    if($Value -isnot [Collections.IDictionary] -or $Value.Count -ne $Fields.Count){throw 'INVALID_PROVISION_FIELDS'}
    foreach($field in $Fields){if(-not $Value.Contains($field)){throw 'INVALID_PROVISION_FIELDS'}}
}
function Assert-LocalPath([string]$Path,[string]$Within=''){
    if($Path -notmatch '^[A-Za-z]:[\\/]' -or $Path -match '(^|[\\/])\.\.([\\/]|$)' -or $Path.Substring(2).Contains(':')){throw 'UNSAFE_PROVISION_PATH'}
    $resolved=[IO.Path]::GetFullPath($Path)
    $cursor=Get-Item -LiteralPath $resolved -Force
    $inside= -not $Within
    while($null -ne $cursor){
        if(($cursor.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'UNSAFE_PROVISION_ALIAS'}
        if($Within -and [StringComparer]::OrdinalIgnoreCase.Equals($cursor.FullName,$Within)){$inside=$true}
        if($cursor -is [IO.DirectoryInfo]){$cursor=$cursor.Parent}else{$cursor=$cursor.Directory}
    }
    if(-not $inside){throw 'PROVISION_PATH_ESCAPE'}
    return $resolved
}
function Assert-Pin([string]$Path,[string]$Pin,[long]$Size=-1){
    if($Pin -notmatch '^sha256:[a-f0-9]{64}$' -or ($Size -ge 0 -and (Get-Item -LiteralPath $Path).Length -ne $Size) -or ('sha256:'+(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()) -ne $Pin){throw 'PROVISION_PIN_MISMATCH'}
}
function Write-Exclusive([string]$Path,[object]$Value){
    $bytes=[Text.Encoding]::UTF8.GetBytes(($Value | ConvertTo-Json -Depth 12 -Compress))
    $file=[IO.File]::Open($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try{$file.Write($bytes,0,$bytes.Length);$file.Flush($true)}finally{$file.Dispose()}
}

$requestFile=Assert-LocalPath $Request
if((Get-Item -LiteralPath $requestFile).Length -gt 65536){throw 'PROVISION_REQUEST_WORKER_REQUIRED'}
Assert-Pin $requestFile $RequestPin
$spec=Get-Content -LiteralPath $requestFile -Raw | ConvertFrom-Json -AsHashtable
Assert-Fields $spec @('schema','attempt','workspace','launch','fence','driverPin','baseRoot','disk','images','seed','network','resources')
if($spec.schema -ne 'hyperv-provision-request:m8.4-v1' -or $spec.attempt -cne ([Guid]$spec.attempt).ToString() -or $spec.launch -notmatch '^sha256:[a-f0-9]{64}$' -or $spec.fence -isnot [long] -and $spec.fence -isnot [int] -or $spec.fence -lt 1){throw 'INVALID_PROVISION_SCOPE'}
$workspace=Assert-LocalPath $spec.workspace
Assert-LocalPath $requestFile $workspace | Out-Null
$base=Assert-LocalPath $spec.baseRoot
$disk=Assert-LocalPath $spec.disk $base
if($spec.images -isnot [array] -or $spec.images.Count -lt 1){throw 'IMAGE_CHAIN_REQUIRED'}
$paths=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach($image in $spec.images){
    Assert-Fields $image @('path','size','digest')
    $path=Assert-LocalPath $image.path $base
    if(-not $paths.Add($path) -or $image.size -isnot [long] -and $image.size -isnot [int] -or $image.size -lt 1){throw 'INVALID_IMAGE_CHAIN'}
    Assert-Pin $path $image.digest $image.size
    if(((Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReadOnly) -eq 0){throw 'IMAGE_CHAIN_NOT_READONLY'}
}
if(-not $paths.Contains($disk)){throw 'IMAGE_CHAIN_DISK_MISSING'}
$chain=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
$next=$disk
while($next){
    $next=Assert-LocalPath $next $base
    if(-not $paths.Contains($next) -or -not $chain.Add($next)){throw 'IMAGE_CHAIN_DENOMINATOR_MISMATCH'}
    $next=(Get-VHD -Path $next -ErrorAction Stop).ParentPath
}
if($chain.Count -ne $paths.Count){throw 'IMAGE_CHAIN_DENOMINATOR_MISMATCH'}
Assert-Fields $spec.seed @('path','size','digest')
if($spec.seed.size -isnot [int] -and $spec.seed.size -isnot [long] -or $spec.seed.size -lt 1){throw 'INVALID_PROVISION_SEED'}
$seed=Assert-LocalPath $spec.seed.path $workspace
Assert-Pin $seed $spec.seed.digest $spec.seed.size
Assert-Fields $spec.network @('switch','mac','guest','controller')
if($spec.network.mac -notmatch '^[0-9A-F]{12}$' -or $spec.network.guest -notmatch '^\d+\.\d+\.\d+\.\d+$' -or $spec.network.controller -notmatch '^\d+\.\d+\.\d+\.\d+$'){throw 'INVALID_PROVISION_NETWORK'}
$guestIP=[Net.IPAddress]::Parse($spec.network.guest);$controllerIP=[Net.IPAddress]::Parse($spec.network.controller)
if($guestIP.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or $controllerIP.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or $guestIP.Equals($controllerIP)){throw 'INVALID_PROVISION_NETWORK'}
$switch=Get-VMSwitch -Name $spec.network.switch -ErrorAction Stop
if($switch.SwitchType.ToString() -ne 'Internal'){throw 'INTERNAL_SWITCH_REQUIRED'}
Assert-Fields $spec.resources @('processors','memoryBytes')
if($spec.resources.processors -isnot [int] -and $spec.resources.processors -isnot [long] -or $spec.resources.processors -lt 1 -or $spec.resources.processors -gt [int]::MaxValue -or $spec.resources.memoryBytes -isnot [long] -and $spec.resources.memoryBytes -isnot [int] -or $spec.resources.memoryBytes -lt 268435456){throw 'INVALID_PROVISION_RESOURCES'}
$driver=Assert-LocalPath (Join-Path $PSScriptRoot 'worker.ps1')
Assert-Pin $driver $spec.driverPin
$name='evo-worker-'+$spec.attempt.Replace('-','')
$ownerPath=Join-Path $workspace ('owner-'+$spec.attempt.Replace('-','')+'.json')
function Assert-Configured([object]$VM){
    $adapters=@(Get-VMNetworkAdapter -VM $VM)
    if($adapters.Count -ne 1 -or $adapters[0].MacAddress -ne $spec.network.mac -or $adapters[0].SwitchName -ne $spec.network.switch -or $adapters[0].DhcpGuard.ToString() -ne 'On' -or $adapters[0].RouterGuard.ToString() -ne 'On' -or $adapters[0].MacAddressSpoofing.ToString() -ne 'Off'){throw 'PROVISION_NETWORK_CHANGED'}
    $rules=@(Get-VMNetworkAdapterExtendedAcl -VMName $name)
    $outDeny=@($rules | Where-Object {$_.Direction.ToString() -eq 'Outbound' -and $_.Action.ToString() -eq 'Deny' -and $_.Weight -eq 1 -and $_.LocalIPAddress -eq 'ANY' -and $_.RemoteIPAddress -eq 'ANY' -and $_.Protocol -eq 'ANY'})
    $inDeny=@($rules | Where-Object {$_.Direction.ToString() -eq 'Inbound' -and $_.Action.ToString() -eq 'Deny' -and $_.Weight -eq 1 -and $_.LocalIPAddress -eq 'ANY' -and $_.RemoteIPAddress -eq 'ANY' -and $_.Protocol -eq 'ANY'})
    $ssh=@($rules | Where-Object {$_.Direction.ToString() -eq 'Inbound' -and $_.Action.ToString() -eq 'Allow' -and $_.Weight -eq 100 -and $_.LocalIPAddress -eq $spec.network.guest -and $_.RemoteIPAddress -eq $spec.network.controller -and $_.Protocol -eq 'TCP' -and $_.LocalPort -eq '22' -and $_.RemotePort -eq 'ANY' -and $_.Stateful})
    if($rules.Count -ne 3 -or $outDeny.Count -ne 1 -or $inDeny.Count -ne 1 -or $ssh.Count -ne 1){throw 'PROVISION_ACL_CHANGED'}
    foreach($service in @('Guest Service Interface','Key-Value Pair Exchange','Shutdown','VSS')){if((Get-VMIntegrationService -VMName $name -Name $service).Enabled){throw 'PROVISION_INTEGRATION_CHANGED'}}
    $dvd=@(Get-VMDvdDrive -VMName $name)
    if($dvd.Count -ne 1 -or -not [StringComparer]::OrdinalIgnoreCase.Equals($dvd[0].Path,$seed)){throw 'PROVISION_SEED_CHANGED'}
    if($VM.ProcessorCount -ne $spec.resources.processors -or $VM.MemoryStartup -ne $spec.resources.memoryBytes){throw 'PROVISION_RESOURCES_CHANGED'}
    $cpu=Get-VMProcessor -VM $VM
    if($cpu.ExposeVirtualizationExtensions -or -not $cpu.EnableHostResourceProtection){throw 'PROVISION_PROCESSOR_POLICY_CHANGED'}
}
if($Action -eq 'Prepare'){
    $preparedPath=Join-Path $workspace 'provision-prepared.json'
    if(Test-Path -LiteralPath $preparedPath){
        $previous=Get-Content -LiteralPath (Assert-LocalPath $preparedPath $workspace) -Raw | ConvertFrom-Json
        if($previous.request -ne $RequestPin){throw 'PROVISION_REQUEST_CONFLICT'}
        $found=(& $driver -Action Find -Workspace $workspace -Attempt $spec.attempt -LaunchDigest $spec.launch -Fence $spec.fence) | ConvertFrom-Json
        if($found.vmId -ne $previous.vmId){throw 'PROVISION_WORKER_CHANGED'}
        Assert-Configured (Get-VM -Id ([Guid]$found.vmId))
        $found | ConvertTo-Json -Compress
        exit
    }
    $existing=Get-VM -Name $name -ErrorAction SilentlyContinue
    if($null -ne $existing -and $existing.State.ToString() -ne 'Off'){throw 'PROVISION_REQUIRES_OFF'}
    $created=(& $driver -Action Create -Workspace $workspace -Attempt $spec.attempt -ExportedDisk $disk -BaseRoot $base -Processors $spec.resources.processors -MemoryBytes $spec.resources.memoryBytes) | ConvertFrom-Json
    $bound=(& $driver -Action Bind -Workspace $workspace -Attempt $spec.attempt -LaunchDigest $spec.launch -Fence $spec.fence) | ConvertFrom-Json
    if($created.vmId -ne $bound.vmId -or $bound.state -ne 'Off'){throw 'PROVISION_CREATE_SCOPE_MISMATCH'}
    $vm=Get-VM -Id ([Guid]$bound.vmId)
    $adapters=@(Get-VMNetworkAdapter -VM $vm)
    if($adapters.Count -ne 1){throw 'PROVISION_SINGLE_ADAPTER_REQUIRED'}
    Connect-VMNetworkAdapter -VMNetworkAdapter $adapters[0] -SwitchName $spec.network.switch
    Set-VMNetworkAdapter -VMNetworkAdapter $adapters[0] -StaticMacAddress $spec.network.mac -DhcpGuard On -RouterGuard On -MacAddressSpoofing Off
    $acls=@(Get-VMNetworkAdapterExtendedAcl -VMName $name)
    if($acls.Count -ne 0){throw 'PROVISION_NETWORK_FRONTIER_ALREADY_CONFIGURED'}
    Add-VMNetworkAdapterExtendedAcl -VMName $name -Action Deny -Direction Outbound -Weight 1
    Add-VMNetworkAdapterExtendedAcl -VMName $name -Action Deny -Direction Inbound -Weight 1
    Add-VMNetworkAdapterExtendedAcl -VMName $name -Action Allow -Direction Inbound -Weight 100 -LocalIPAddress $spec.network.guest -RemoteIPAddress $spec.network.controller -Protocol TCP -LocalPort 22 -Stateful $true -IdleSessionTimeout 255
    foreach($service in @('Guest Service Interface','Key-Value Pair Exchange','Shutdown','VSS')){Disable-VMIntegrationService -VMName $name -Name $service}
    Set-VMProcessor -VMName $name -ExposeVirtualizationExtensions $false -EnableHostResourceProtection $true
    if(@(Get-VMDvdDrive -VMName $name).Count -ne 0){throw 'PROVISION_DVD_FRONTIER_ALREADY_CONFIGURED'}
    Add-VMDvdDrive -VMName $name -Path $seed
    Assert-Configured $vm
    Write-Exclusive (Join-Path $workspace 'provision-prepared.json') ([ordered]@{schema='hyperv-provision-prepared:m8.4-v1';request=$RequestPin;attempt=$spec.attempt;launch=$spec.launch;fence=$spec.fence;vmId=$vm.Id.ToString();qualified=$false})
    $bound | ConvertTo-Json -Compress
}else{
    $prepared=Get-Content -LiteralPath (Assert-LocalPath (Join-Path $workspace 'provision-prepared.json') $workspace) -Raw | ConvertFrom-Json
    $ready=Get-Content -LiteralPath (Assert-LocalPath (Join-Path $workspace 'supervisor-ready.json') $workspace) -Raw | ConvertFrom-Json
    if($prepared.request -ne $RequestPin -or $prepared.attempt -ne $spec.attempt -or $ready.attempt -ne $spec.attempt -or $ready.fence -ne $spec.fence -or $ready.vmId -ne $prepared.vmId){throw 'PROVISION_SUPERVISOR_SCOPE_MISMATCH'}
    $found=(& $driver -Action Find -Workspace $workspace -Attempt $spec.attempt -LaunchDigest $spec.launch -Fence $spec.fence) | ConvertFrom-Json
    Assert-Configured (Get-VM -Id ([Guid]$found.vmId))
    $frontier=Join-Path $workspace 'provision-launch-intent.json'
    if(Test-Path -LiteralPath $frontier){
        if($found.state -ne 'Running'){throw 'PROVISION_LAUNCH_FRONTIER_RECONCILIATION_REQUIRED'}
    }else{
        if($found.state -ne 'Off'){throw 'PROVISION_EXPECTED_OFF'}
        Write-Exclusive $frontier ([ordered]@{request=$RequestPin;attempt=$spec.attempt;launch=$spec.launch;fence=$spec.fence;vmId=$prepared.vmId})
        $found=(& $driver -Action Start -Workspace $workspace -Attempt $spec.attempt -LaunchDigest $spec.launch -Fence $spec.fence) | ConvertFrom-Json
    }
    $found | ConvertTo-Json -Compress
}
