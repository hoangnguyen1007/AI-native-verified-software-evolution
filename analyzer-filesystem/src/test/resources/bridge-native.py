"""Fixed first-party Windows ABI fixture; never accepts target code or paths.

This tests the declared Python/WinAPI registration only. It is not a general
native provider, JNI/PInvoke certificate, or target-build host fallback.
"""
import ctypes
import hashlib
import json
import os
from pathlib import Path
import struct
import sys
import _ctypes


def sha(path):
    value = hashlib.sha256()
    with Path(path).open('rb') as stream:
        while block := stream.read(65536):
            value.update(block)
    return 'sha256:' + value.hexdigest()


if sys.platform != 'win32' or ctypes.sizeof(ctypes.c_void_p) != 8:
    raise RuntimeError('BRIDGE_FIXTURE_REQUIRES_WINDOWS_X64_PYTHON')
kernel = ctypes.WinDLL('kernel32.dll', winmode=0x800)  # system directory only
system_directory = kernel.GetSystemDirectoryW
system_directory.argtypes = [ctypes.c_wchar_p, ctypes.c_uint32]
system_directory.restype = ctypes.c_uint32
buffer = ctypes.create_unicode_buffer(32768)
length = system_directory(buffer, len(buffer))
if not 0 < length < len(buffer):
    raise RuntimeError('BRIDGE_NATIVE_SYSTEM_DIRECTORY_UNAVAILABLE')
library = Path(buffer.value) / 'kernel32.dll'
with library.open('rb') as source:
    header = source.read(64)
    source.seek(struct.unpack_from('<I', header, 60)[0])
    pe = source.read(6)
if pe[:4] != b'PE\0\0' or struct.unpack_from('<H', pe, 4)[0] != 0x8664:
    raise RuntimeError('BRIDGE_NATIVE_TARGET_ABI_MISMATCH')
target = kernel.GetCurrentProcessId
target.argtypes = []
target.restype = ctypes.c_uint32
if target() != os.getpid():
    raise RuntimeError('BRIDGE_NATIVE_INDEPENDENT_PID_MISMATCH')
try:
    kernel.EvoDefinitelyMissingBridgeSymbol
    raise RuntimeError('BRIDGE_NATIVE_MISSING_SYMBOL_WAS_ACCEPTED')
except AttributeError:
    pass
try:
    ctypes.WinDLL(str(library.parent / 'evo-definitely-missing-bridge.dll'), winmode=0x800)
    raise RuntimeError('BRIDGE_NATIVE_MISSING_LIBRARY_WAS_ACCEPTED')
except OSError:
    pass
print(json.dumps(dict(schema='authored-python-winapi-bridge:m8.4-v1',
                     library=sha(library), runtime=sha(sys.executable),
                     adapter=sha(_ctypes.__file__), symbol='GetCurrentProcessId',
                     signature='uint32(void)', abi='PE32+:AMD64:windows',
                     callingConvention='WINAPI-x64', positive=True,
                     missingSymbolRejected=True, missingLibraryRejected=True),
                 sort_keys=True, separators=(',', ':')))
