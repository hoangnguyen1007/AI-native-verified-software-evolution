#requires -Version 7.0
param(
    [Parameter(Mandatory=$true)][ValidateSet('Create','Inspect','Start','Stop','Bind','Find')][string]$Action,
    [Parameter(Mandatory=$true)][string]$Workspace,
    [Parameter(Mandatory=$true)][Guid]$Attempt,
    [string]$ExportedDisk,
    [string]$BaseRoot,
    [int]$Processors=2,
    [long]$MemoryBytes=2147483648,
    [string]$LaunchDigest,
    [long]$Fence=0
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest

# Trusted controller helper: identify aliases without treating independent exported bytes as the same file.
if(-not ('EvoM843FileIdentity' -as [type])){
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
public static class EvoM843FileIdentity {
    [StructLayout(LayoutKind.Sequential)] private struct Info {
        public uint Attributes;
        public System.Runtime.InteropServices.ComTypes.FILETIME Created, Accessed, Written;
        public uint VolumeSerial, SizeHigh, SizeLow, Links, IndexHigh, IndexLow;
    }
    [DllImport("kernel32.dll", SetLastError=true)]
    private static extern bool GetFileInformationByHandle(SafeFileHandle handle, out Info info);
    public static string Get(string path) {
        using(var file=new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.ReadWrite|FileShare.Delete)) {
            Info info;
            if(!GetFileInformationByHandle(file.SafeFileHandle,out info)) throw new IOException("BASE_FILE_IDENTITY_UNAVAILABLE");
            return info.VolumeSerial.ToString("X8")+":"+info.IndexHigh.ToString("X8")+info.IndexLow.ToString("X8");
        }
    }
}
'@
}
function Get-LocalFileIdentity([string]$Path) {
    if($Path -notmatch '^[A-Za-z]:[\\/]'){throw 'REMOTE_VM_STORAGE_IDENTITY_REQUIRED'}
    return [EvoM843FileIdentity]::Get([IO.Path]::GetFullPath($Path))
}
function Assert-SafeDirectory([string]$Path) {
    $resolved=[IO.Path]::GetFullPath($Path)
    $cursor=Get-Item -LiteralPath $resolved -Force
    if($cursor -isnot [IO.DirectoryInfo]){throw 'WORKSPACE_NOT_DIRECTORY'}
    while($null -ne $cursor){
        if(($cursor.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'WORKSPACE_REPARSE_POINT'}
        $cursor=$cursor.Parent
    }
    return $resolved
}
function Assert-Contained([string]$Child,[string]$Root) {
    $cursor=Get-Item -LiteralPath ([IO.Path]::GetFullPath($Child)) -Force
    while($null -ne $cursor){
        if(($cursor.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'PATH_REPARSE_POINT'}
        if([StringComparer]::OrdinalIgnoreCase.Equals($cursor.FullName,$Root)){return}
        if($cursor -is [IO.DirectoryInfo]){$cursor=$cursor.Parent}else{$cursor=$cursor.Directory}
    }
    throw 'PATH_OUTSIDE_WORKSPACE'
}
function Write-OwnerAtomically([object]$Owner,[string]$Destination) {
    $temporary=$Destination+'.'+[Guid]::NewGuid().ToString('N')+'.pending'
    $bytes=[Text.Encoding]::UTF8.GetBytes(($Owner | ConvertTo-Json -Compress))
    $file=[IO.File]::Open($temporary,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try{$file.Write($bytes,0,$bytes.Length);$file.Flush($true)}finally{$file.Dispose()}
    # Atomic replacement retains the previous complete ownership record if publication fails.
    [IO.File]::Move($temporary,$Destination,$true)
}
$root=Assert-SafeDirectory $Workspace
$inputRoot=if([string]::IsNullOrEmpty($BaseRoot)){$root}else{Assert-SafeDirectory $BaseRoot}
$name='evo-worker-'+$Attempt.ToString('N')
$marker=Join-Path $root ('owner-'+$Attempt.ToString('N')+'.json')
if(($LaunchDigest -and ($LaunchDigest -notmatch '^sha256:[a-f0-9]{64}$' -or $Fence -lt 1)) -or (-not $LaunchDigest -and $Fence -ne 0)){throw 'INVALID_LAUNCH_BINDING'}
if(($Action -eq 'Bind' -or $Action -eq 'Find') -and -not $LaunchDigest){throw 'LAUNCH_BINDING_REQUIRED'}
if($Action -eq 'Find' -and -not (Test-Path -LiteralPath $marker)){
    # Enumerating successfully is essential: a service/permission failure must never mean ABSENT.
    $candidate=@(Get-VM -ErrorAction Stop | Where-Object {$null -ne $_ -and $_.Name -eq $name})
    if($candidate.Count -ne 0 -or (Test-Path -LiteralPath (Join-Path $root $name))){throw 'WORKER_NAMESPACE_UNVERIFIED'}
    [ordered]@{schema='hyperv-worker-state:m8.4-v1';attempt=$Attempt.ToString();name=$name;vmId='';state='Absent';processors=0;memoryBytes=0;isolationQualified=$false;launch=$LaunchDigest;fence=$Fence} | ConvertTo-Json -Compress
    return
}

if($Action -eq 'Create'){
    if($Processors -lt 1 -or $Processors -gt 4 -or $MemoryBytes -lt 1073741824 -or $MemoryBytes -gt 4294967296){throw 'RESOURCE_POLICY_REQUIRED'}
    if(Test-Path -LiteralPath $marker){
        $existingOwner=Get-Content -LiteralPath $marker -Raw | ConvertFrom-Json
        $existingVM=Get-VM -Name $name -ErrorAction SilentlyContinue
        if($null -ne $existingVM -and $null -ne $existingOwner.vmId -and $existingOwner.vmId -ne $existingVM.Id.ToString()){throw 'WORKER_RECOVERY_ID_MISMATCH'}
    }
    $disk=[IO.Path]::GetFullPath($ExportedDisk)
    Assert-Contained $disk $inputRoot
    $chain=$disk
    $baseNodes=[Collections.Generic.List[object]]::new()
    $seenBase=[Collections.Generic.HashSet[string]]::new()
    while($chain){
        Assert-Contained $chain $inputRoot
        $identity=Get-LocalFileIdentity $chain
        if(-not $seenBase.Add($identity)){throw 'BASE_PARENT_CYCLE'}
        $vhd=Get-VHD -Path $chain -ErrorAction Stop
        $baseNodes.Add([pscustomobject]@{identity=$identity;descriptor=$vhd})
        $chain=$vhd.ParentPath
    }
    $directDisks=[Collections.Generic.HashSet[string]]::new()
    $ownedReadonlyParents=[Collections.Generic.HashSet[string]]::new()
    foreach($attachedVM in @(Get-VM)){
        if($null -eq $attachedVM){continue}
        foreach($attachedDisk in @(Get-VMHardDiskDrive -VM $attachedVM)){
            if([string]::IsNullOrEmpty($attachedDisk.Path)){continue}
            [void]$directDisks.Add((Get-LocalFileIdentity $attachedDisk.Path))
            # Only owned child metadata can authorize an attached readonly parent.
            if($attachedVM.Name -notmatch '^evo-worker-([a-f0-9]{32})$'){continue}
            $attachedMarker=Join-Path $root ('owner-'+$Matches[1]+'.json')
            if(-not (Test-Path -LiteralPath $attachedMarker)){continue}
            $attachedOwner=Get-Content -LiteralPath $attachedMarker -Raw | ConvertFrom-Json
            if($attachedOwner.schema -ne 'hyperv-worker-owner:m8.4-v1' -or $attachedOwner.vmId -ne $attachedVM.Id.ToString() -or $attachedOwner.workspace -ne $root -or $attachedOwner.disk -ne $attachedDisk.Path){continue}
            Assert-Contained $attachedDisk.Path $root
            $childDescriptor=Get-VHD -Path $attachedDisk.Path -ErrorAction Stop
            if($childDescriptor.ParentPath -ne $attachedOwner.baseDisk){throw 'WORKER_PARENT_MISMATCH'}
            [void]$ownedReadonlyParents.Add((Get-LocalFileIdentity $childDescriptor.ParentPath))
        }
    }
    $knownReadonlyChain=$false
    foreach($node in $baseNodes){
        if($directDisks.Contains($node.identity)){throw 'IMMUTABLE_BASE_DIRECTLY_ATTACHED'}
        if($null -ne $node.descriptor.DiskNumber){throw 'IMMUTABLE_BASE_HOST_MOUNTED'}
        if($ownedReadonlyParents.Contains($node.identity)){$knownReadonlyChain=$true}
        if($node.descriptor.Attached -and -not $knownReadonlyChain){throw 'IMMUTABLE_BASE_ATTACHMENT_UNVERIFIED'}
    }
    # New-VHD requires a .vhdx parent extension even for valid exported AVHDX bytes.
    # A new hardlink preserves exact immutable bytes and relative parent locators.
    if([IO.Path]::GetExtension($disk) -eq '.avhdx'){
        $compatible=Join-Path ([IO.Path]::GetDirectoryName($disk)) ([IO.Path]::GetFileNameWithoutExtension($disk)+'.pinned.vhdx')
        if(-not (Test-Path -LiteralPath $compatible)){New-Item -ItemType HardLink -Path $compatible -Target $disk | Out-Null}
        $originalHash=(Get-FileHash -LiteralPath $disk -Algorithm SHA256).Hash
        if((Get-FileHash -LiteralPath $compatible -Algorithm SHA256).Hash -ne $originalHash){throw 'BASE_IMAGE_ALIAS_MISMATCH'}
        $disk=$compatible
    }
    $workerDirectory=Join-Path $root $name
    $privateDisk=Join-Path $workerDirectory 'private-overlay.vhdx'
    if(Test-Path -LiteralPath $marker){
        $intent=Get-Content -LiteralPath $marker -Raw | ConvertFrom-Json -AsHashtable
        if($intent.schema -ne 'hyperv-worker-owner:m8.4-v1' -or $intent.attempt -ne $Attempt.ToString() -or $intent.name -ne $name -or $intent.workspace -ne $root -or $intent.disk -ne $privateDisk -or $intent.baseDisk -ne $disk){throw 'ATTEMPT_INTENT_CONFLICT'}
    }else{
        if((Get-VM -Name $name -ErrorAction SilentlyContinue) -or (Test-Path -LiteralPath $workerDirectory)){throw 'WORKER_NAMESPACE_ALREADY_EXISTS'}
        $intent=[ordered]@{schema='hyperv-worker-owner:m8.4-v1';attempt=$Attempt.ToString();name=$name;vmId=$null;disk=$privateDisk;baseDisk=$disk;workspace=$root;phase='CREATE_INTENT'}
        # Write-through intent before creating VM resources; the actor can recover by exact name/path.
        $bytes=[Text.Encoding]::UTF8.GetBytes(($intent | ConvertTo-Json -Compress))
        $file=[IO.File]::Open($marker,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        try{$file.Write($bytes,0,$bytes.Length);$file.Flush($true)}finally{$file.Dispose()}
    }
    if(-not (Test-Path -LiteralPath $workerDirectory)){New-Item -ItemType Directory -Path $workerDirectory | Out-Null}
    if(-not (Test-Path -LiteralPath $privateDisk)){New-VHD -Path $privateDisk -ParentPath $disk -Differencing | Out-Null}
    $child=Get-VHD -Path $privateDisk
    if(-not [StringComparer]::OrdinalIgnoreCase.Equals($child.ParentPath,$disk)){throw 'WORKER_PARENT_MISMATCH'}
    $vm=Get-VM -Name $name -ErrorAction SilentlyContinue
    if($null -eq $vm){$vm=New-VM -Name $name -Generation 2 -MemoryStartupBytes $MemoryBytes -VHDPath $privateDisk -Path $workerDirectory}
    $currentDisks=@(Get-VMHardDiskDrive -VM $vm)
    if($currentDisks.Count -ne 1 -or $currentDisks[0].Path -ne $privateDisk){throw 'WORKER_RECOVERY_DISK_MISMATCH'}
    if($null -ne $intent.vmId -and $intent.vmId -ne $vm.Id.ToString()){throw 'WORKER_RECOVERY_ID_MISMATCH'}
    if($vm.State.ToString() -eq 'Off'){
    Set-VM -VM $vm -AutomaticCheckpointsEnabled $false -AutomaticStartAction Nothing -AutomaticStopAction TurnOff
    Set-VMProcessor -VM $vm -Count $Processors -Maximum 50
    Set-VMMemory -VM $vm -DynamicMemoryEnabled $false -StartupBytes $MemoryBytes
    Set-VMFirmware -VM $vm -EnableSecureBoot Off
    }elseif($vm.ProcessorCount -ne $Processors -or $vm.MemoryStartup -ne $MemoryBytes){throw 'WORKER_RESOURCE_PROFILE_MISMATCH'}
    # No network is attached at creation. Management bootstrap must explicitly configure it.
    $intent.vmId=$vm.Id.ToString();$intent.phase='CREATED'
    Write-OwnerAtomically $intent $marker
}
if(-not (Test-Path -LiteralPath $marker)){throw 'WORKER_OWNERSHIP_REQUIRED'}
$owner=Get-Content -LiteralPath $marker -Raw | ConvertFrom-Json
if($owner.schema -ne 'hyperv-worker-owner:m8.4-v1' -or $owner.attempt -ne $Attempt.ToString() -or $owner.name -ne $name -or -not [StringComparer]::OrdinalIgnoreCase.Equals($owner.workspace,$root)){throw 'WORKER_OWNERSHIP_MISMATCH'}
$vm=Get-VM -Id ([Guid]$owner.vmId) -ErrorAction Stop
if($vm.Name -ne $name){throw 'WORKER_VM_ID_MISMATCH'}
$disks=@(Get-VMHardDiskDrive -VM $vm)
if($disks.Count -ne 1 -or -not [StringComparer]::OrdinalIgnoreCase.Equals([IO.Path]::GetFullPath($disks[0].Path),[IO.Path]::GetFullPath($owner.disk))){throw 'WORKER_DISK_MISMATCH'}
Assert-Contained $owner.disk $root
if($LaunchDigest){
    $bound=$owner.PSObject.Properties.Name -contains 'launch'
    if($bound -and ($owner.launch -isnot [string] -or $owner.launch -notmatch '^sha256:[a-f0-9]{64}$' -or $owner.PSObject.Properties.Name -notcontains 'fence')){throw 'INVALID_WORKER_LAUNCH_BINDING'}
    if($bound -and (($owner.fence -isnot [long] -and $owner.fence -isnot [int]) -or $owner.fence -lt 1)){throw 'INVALID_WORKER_LAUNCH_BINDING'}
    if($Action -eq 'Bind'){
        if($bound -and ($owner.launch -ne $LaunchDigest -or $owner.fence -ne $Fence)){throw 'WORKER_LAUNCH_BINDING_CONFLICT'}
        if($vm.State.ToString() -ne 'Off'){throw 'WORKER_BIND_REQUIRES_OFF'}
        if(-not $bound){
            $owner | Add-Member -NotePropertyName launch -NotePropertyValue $LaunchDigest
            $owner | Add-Member -NotePropertyName fence -NotePropertyValue $Fence
            Write-OwnerAtomically $owner $marker
        }
    }elseif(-not $bound -or $owner.launch -ne $LaunchDigest -or $owner.fence -ne $Fence){throw 'WORKER_LAUNCH_BINDING_MISMATCH'}
}
if($Action -eq 'Start'){Start-VM -VM $vm | Out-Null}
if($Action -eq 'Stop' -and $vm.State.ToString() -ne 'Off'){Stop-VM -VM $vm -TurnOff -Confirm:$false | Out-Null}
$vm=Get-VM -Id $vm.Id
$result=[ordered]@{schema='hyperv-worker-state:m8.4-v1';attempt=$Attempt.ToString();name=$vm.Name;vmId=$vm.Id.ToString();state=$vm.State.ToString();processors=[long]$vm.ProcessorCount;memoryBytes=[long]$vm.MemoryStartup;isolationQualified=$false}
if($LaunchDigest){$result.launch=$LaunchDigest;$result.fence=$Fence}
$result | ConvertTo-Json -Compress
