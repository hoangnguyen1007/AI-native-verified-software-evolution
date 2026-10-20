#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidateSet('Inspect','Initialize')][string]$Action,
    [Parameter(Mandatory=$true)][string]$Workspace,
    [ValidatePattern('^[D-Z]$')][string]$DriveLetter='Z',
    [ValidateRange(1073741824,68719476736)][long]$CapacityBytes=8589934592
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
function Assert-NoAlias([string]$Path){
    $item=Get-Item -LiteralPath $Path -Force
    while($null -ne $item){
        if(($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'HOST_STORAGE_PATH_ALIAS'}
        if($item -is [IO.DirectoryInfo]){$item=$item.Parent}else{$item=$item.Directory}
    }
}
function Write-NewRecord([string]$Path,[object]$Value){
    $bytes=[Text.Encoding]::UTF8.GetBytes(($Value | ConvertTo-Json -Depth 8 -Compress))
    $file=[IO.File]::Open($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try{$file.Write($bytes,0,$bytes.Length);$file.Flush($true)}finally{$file.Dispose()}
}
if($Workspace -notmatch '^[A-Za-z]:[\\/]' -or $Workspace -match '(^|[\\/])\.\.([\\/]|$)'){throw 'ABSOLUTE_LOCAL_WORKSPACE_REQUIRED'}
$workspaceRoot=(Get-Item -LiteralPath ([IO.Path]::GetFullPath($Workspace))).FullName
Assert-NoAlias $workspaceRoot
$parent=Join-Path $workspaceRoot '.evo/executor'
if(-not (Test-Path -LiteralPath $parent -PathType Container)){throw 'EXECUTOR_DIRECTORY_REQUIRED'}
Assert-NoAlias $parent
$store=Join-Path $parent 'm843-host-storage'
$file=Join-Path $store 'host-storage.vhdx'
$principal=[Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
$elevated=$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
$occupied=($null -ne (Get-PSDrive -Name $DriveLetter -ErrorAction SilentlyContinue))
$existing=Test-Path -LiteralPath $store
if($Action -eq 'Inspect'){
    [ordered]@{schema='hyperv-host-storage-preflight:m8.4-v1';administrativeToken=$elevated;hostStore=$file;driveLetter=$DriveLetter;capacityBytes=$CapacityBytes;driveOccupied=$occupied;storeAlreadyExists=$existing;guestDiskAccess=$false} | ConvertTo-Json -Compress
    return
}
# All privilege/existing-resource checks precede the first mutation.
if(-not $elevated){throw 'ADMINISTRATIVE_TOKEN_REQUIRED_FOR_NEW_HOST_STORAGE'}
if($occupied -or $existing -or (Get-Volume -DriveLetter $DriveLetter -ErrorAction SilentlyContinue)){throw 'HOST_STORAGE_RESOURCE_ALREADY_EXISTS'}
$hostVolume=Get-Volume -FilePath $parent
if($hostVolume.SizeRemaining -lt ($CapacityBytes+1073741824)){throw 'HOST_STORAGE_HEADROOM_REQUIRED'}
New-Item -ItemType Directory -Path $store | Out-Null
Assert-NoAlias $store
$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
& icacls.exe $store '/inheritance:r' '/grant:r' ('*'+$sid+':(OI)(CI)F') '*S-1-5-18:(OI)(CI)F' | Out-Null
if($LASTEXITCODE -ne 0){throw 'HOST_STORAGE_PRIVATE_ACL_REQUIRED'}
$intent=[ordered]@{schema='hyperv-new-host-storage-intent:m8.4-v1';kind='NEW_HOST_STORAGE';workspace=$workspaceRoot;path=$file;capacityBytes=$CapacityBytes;driveLetter=$DriveLetter;owner=$sid;guestDiskAccess=$false}
Write-NewRecord (Join-Path $store 'intent.json') $intent
# This file is newly created here. Never select a RAW disk from an enumeration or a guest VM.
New-VHD -Path $file -Fixed -SizeBytes $CapacityBytes -ErrorAction Stop | Out-Null
$mounted=Mount-VHD -Path $file -Passthru -ErrorAction Stop
$disk=$mounted | Get-Disk
$descriptor=Get-VHD -DiskNumber $disk.Number -ErrorAction Stop
if(-not [StringComparer]::OrdinalIgnoreCase.Equals([IO.Path]::GetFullPath($descriptor.Path),$file) -or $disk.Size -ne $CapacityBytes -or $disk.IsBoot -or $disk.IsSystem -or $disk.PartitionStyle.ToString() -ne 'RAW'){throw 'NEW_HOST_STORAGE_DISK_IDENTITY_MISMATCH'}
if($disk.IsOffline){Set-Disk -Number $disk.Number -IsOffline $false -ErrorAction Stop}
if($disk.IsReadOnly){Set-Disk -Number $disk.Number -IsReadOnly $false -ErrorAction Stop}
Initialize-Disk -Number $disk.Number -PartitionStyle GPT -ErrorAction Stop | Out-Null
$partition=New-Partition -DiskNumber $disk.Number -UseMaximumSize -DriveLetter $DriveLetter -ErrorAction Stop
$volume=Format-Volume -Partition $partition -FileSystem NTFS -NewFileSystemLabel 'EVO-M843-HOST' -Confirm:$false -Force -ErrorAction Stop
$letterRoot=$DriveLetter+':\'
if((Get-Partition -DriveLetter $DriveLetter).DiskNumber -ne $disk.Number){throw 'HOST_STORAGE_VOLUME_IDENTITY_MISMATCH'}
$permissions=Get-Acl -LiteralPath $letterRoot
$permissions.SetAccessRuleProtection($true,$false)
foreach($rule in @($permissions.Access)){$permissions.RemoveAccessRuleSpecific($rule)}
$ownerId=[Security.Principal.SecurityIdentifier]::new($sid)
$permissions.SetOwner($ownerId)
foreach($identity in @($ownerId,[Security.Principal.SecurityIdentifier]::new('S-1-5-18'))){
    $permissions.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($identity,[Security.AccessControl.FileSystemRights]::FullControl,[Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit',[Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Allow))
}
Set-Acl -LiteralPath $letterRoot -AclObject $permissions -ErrorAction Stop
$receipt=[ordered]@{schema='hyperv-host-storage:m8.4-v1';kind='HOST_STORAGE';workspace=$workspaceRoot;path=$file;driveLetter=$DriveLetter;volumeUniqueId=$volume.UniqueId;diskNumber=$disk.Number;partitionNumber=$partition.PartitionNumber;capacityBytes=$CapacityBytes;owner=$sid;guestDiskAccess=$false}
Write-NewRecord (Join-Path $store 'provisioned.json') $receipt
$receipt | ConvertTo-Json -Compress
