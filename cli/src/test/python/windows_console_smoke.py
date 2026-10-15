"""Exercise the packaged CLI in an isolated, hidden Windows console with VT initially OFF.

Uses only Python's standard library. Reads the console screen, not redirected stdout,
so raw SGR text cannot masquerade as successful color rendering in a ConPTY capture.
Run from the repository root after packaging: python cli/src/test/python/windows_console_smoke.py
"""
import ctypes
from ctypes import wintypes as w
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


def worker(root):
    import msvcrt
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    keyboard = ctypes.WinDLL("user32", use_last_error=True)
    class Coord(ctypes.Structure):
        _fields_ = [("X", w.SHORT), ("Y", w.SHORT)]
    class Rect(ctypes.Structure):
        _fields_ = [("Left", w.SHORT), ("Top", w.SHORT), ("Right", w.SHORT), ("Bottom", w.SHORT)]
    class Info(ctypes.Structure):
        _fields_ = [("size", Coord), ("cursor", Coord), ("attributes", w.WORD), ("window", Rect), ("maximum", Coord)]
    class Char(ctypes.Union):
        _fields_ = [("UnicodeChar", w.WCHAR), ("AsciiChar", ctypes.c_char)]
    class Cell(ctypes.Structure):
        _fields_ = [("char", Char), ("attributes", w.WORD)]
    class Key(ctypes.Structure):
        _fields_ = [("down", w.BOOL), ("repeat", w.WORD), ("key", w.WORD), ("scan", w.WORD), ("char", w.WCHAR), ("control", w.DWORD)]
    class Event(ctypes.Union):
        _fields_ = [("key", Key), ("padding", ctypes.c_byte * 16)]
    class Input(ctypes.Structure):
        _fields_ = [("kind", w.WORD), ("event", Event)]
    kernel.CreateFileW.argtypes = [w.LPCWSTR, w.DWORD, w.DWORD, w.LPVOID, w.DWORD, w.DWORD, w.HANDLE]
    kernel.CreateFileW.restype = w.HANDLE
    kernel.SetStdHandle.argtypes = [w.DWORD, w.HANDLE]
    kernel.GetConsoleMode.argtypes = [w.HANDLE, ctypes.POINTER(w.DWORD)]
    kernel.SetConsoleMode.argtypes = [w.HANDLE, w.DWORD]
    kernel.GetConsoleScreenBufferInfo.argtypes = [w.HANDLE, ctypes.POINTER(Info)]
    kernel.SetConsoleScreenBufferSize.argtypes = [w.HANDLE, Coord]
    kernel.SetConsoleWindowInfo.argtypes = [w.HANDLE, w.BOOL, ctypes.POINTER(Rect)]
    kernel.SetConsoleCursorPosition.argtypes = [w.HANDLE, Coord]
    kernel.FillConsoleOutputCharacterW.argtypes = [w.HANDLE, w.WCHAR, w.DWORD, Coord, ctypes.POINTER(w.DWORD)]
    kernel.FillConsoleOutputAttribute.argtypes = [w.HANDLE, w.WORD, w.DWORD, Coord, ctypes.POINTER(w.DWORD)]
    kernel.ReadConsoleOutputW.argtypes = [w.HANDLE, ctypes.POINTER(Cell), Coord, Coord, ctypes.POINTER(Rect)]
    kernel.FlushConsoleInputBuffer.argtypes = [w.HANDLE]
    kernel.WriteConsoleInputW.argtypes = [w.HANDLE, ctypes.POINTER(Input), w.DWORD, ctypes.POINTER(w.DWORD)]
    keyboard.VkKeyScanW.argtypes = [w.WCHAR]
    keyboard.VkKeyScanW.restype = w.SHORT
    handle = kernel.CreateFileW("CONOUT$", 0xC0000000, 3, None, 3, 0, None)
    assert handle not in (None, ctypes.c_void_p(-1).value), "CONOUT$ unavailable"
    assert kernel.SetStdHandle(w.DWORD(-11), handle)
    assert kernel.SetStdHandle(w.DWORD(-12), handle)
    input_handle = kernel.CreateFileW("CONIN$", 0xC0000000, 3, None, 3, 0, None)
    assert input_handle not in (None, ctypes.c_void_p(-1).value)
    assert kernel.SetStdHandle(w.DWORD(-10), input_handle)
    input_fd = msvcrt.open_osfhandle(input_handle, os.O_RDONLY)
    output_fd = msvcrt.open_osfhandle(handle, os.O_WRONLY)
    count = w.DWORD()
    window = Rect(0, 0, 79, 23)
    assert kernel.SetConsoleWindowInfo(handle, True, ctypes.byref(window))
    assert kernel.SetConsoleScreenBufferSize(handle, Coord(80, 500))
    environment = dict(os.environ)
    environment["COLUMNS"] = "120"  # Deliberately stale: native window width must win.
    for key in ("TERM", "WT_SESSION", "ANSICON", "ConEmuANSI", "COLORTERM", "NO_COLOR"):
        environment.pop(key, None)
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java.exe") if os.environ.get("JAVA_HOME") else "java"
    journey_workspace = tempfile.TemporaryDirectory(prefix="evo-console-")
    scan = subprocess.run([java, "-jar", str(root / "cli/target/evo.jar"), "scan", str(root / "cli/src/test/resources/fixtures/tiny"),
                           "-w", journey_workspace.name, "--json"], cwd=root, env=environment, capture_output=True, timeout=25)
    assert scan.returncode in (0, 3) and not scan.stderr, "Native journey setup scan failed"
    assert json.loads(scan.stdout)["data"]["metrics"]["declaredTypes"] > 0
    commands = [
        ("standalone", [java, "-jar", str(root / "cli/target/evo.jar"), "--lang", "vi", "--color", "always", "--width", "80", "help"], 850, 80),
        ("cmd-launcher", ["cmd.exe", "/d", "/c", "call evo.cmd --lang vi --color always --width 80 help"], 850, 80),
        ("cmd-auto-width", ["cmd.exe", "/d", "/c", "call evo.cmd --lang vi --color always help"], 850, 48),
        ("cmd-interactive", ["cmd.exe", "/d", "/c", "call evo.cmd --lang vi --color always --width 80"], 850, 80),
        # A raw command line preserves CMD quotes; list2cmdline uses C-runtime backslash quoting.
        ("cmd-journey", f'cmd.exe /d /c call evo.cmd --lang vi --color always --width 80 -w "{journey_workspace.name}"', 850, 80),
    ]
    # Recent cmd.exe can enable VT itself on startup. Measure its own mode change,
    # rather than incorrectly attributing a shell-owned flag to the Java application.
    assert kernel.SetConsoleMode(handle, 3)
    subprocess.run(["cmd.exe", "/d", "/c", "ver >nul"], cwd=root, env=environment, timeout=10, check=True)
    shell_mode = w.DWORD()
    assert kernel.GetConsoleMode(handle, ctypes.byref(shell_mode))
    results = []
    for name, command, page, width in commands:
        window = Rect(0, 0, width - 1, 23)
        assert kernel.SetConsoleWindowInfo(handle, True, ctypes.byref(window))
        assert kernel.SetConsoleMode(handle, 3), "Cannot disable VT"
        assert kernel.SetConsoleOutputCP(page)
        assert kernel.SetConsoleCP(page)
        assert kernel.FillConsoleOutputCharacterW(handle, " ", 80 * 500, Coord(0, 0), ctypes.byref(count))
        assert kernel.FillConsoleOutputAttribute(handle, 7, 80 * 500, Coord(0, 0), ctypes.byref(count))
        assert kernel.SetConsoleCursorPosition(handle, Coord(0, 0))
        if name in ("cmd-interactive", "cmd-journey"):
            assert kernel.FlushConsoleInputBuffer(input_handle)
            script = "evo show\revo find Controller\rdeps 1\rimpact 1\revo find -n 1\revo next\rlang en\rquit\r" if name == "cmd-journey" else "lang en\rquit\r"
            records = (Input * len(script))()
            for index, char in enumerate(script):
                virtual = 13 if char == "\r" else keyboard.VkKeyScanW(char)
                assert virtual != -1, "No keyboard mapping for scripted character"
                modifiers = virtual >> 8
                control = (0x10 if modifiers & 1 else 0) | (0x08 if modifiers & 2 else 0) | (0x02 if modifiers & 4 else 0)
                records[index].kind = 1
                records[index].event.key = Key(True, 1, virtual & 0xff, 0, char, control)
            assert kernel.WriteConsoleInputW(input_handle, records, len(script), ctypes.byref(count))
        completed = subprocess.run(command, cwd=root, env=environment, stdin=input_fd, stdout=output_fd, stderr=output_fd, timeout=25, check=False)
        cells = (Cell * (80 * 500))()
        region = Rect(0, 0, 79, 499)
        assert kernel.ReadConsoleOutputW(handle, cells, Coord(80, 500), Coord(0, 0), ctypes.byref(region))
        screen = "\n".join("".join(cells[y * 80 + x].char.UnicodeChar for x in range(80)) for y in range(500))
        assert completed.returncode == 0, f"{name}: exit {completed.returncode}; screen={screen.strip()[-700:]}"
        assert "\x1b" not in screen and "[38;2;" not in screen and "[0m" not in screen, f"{name}: raw ANSI printed into console screen"
        assert "Bắt đầu" in screen and "Kiểm tra quy tắc" in screen and "Giải thích độ tin cậy" in screen, f"{name}: Vietnamese text corrupted"
        assert "Java 21" not in screen and "ARCHITECTURE INTELLIGENCE" not in screen
        if name == "cmd-interactive":
            assert "evo >" in screen and "English" in screen, "Palette did not read language switch / quit"
        if name == "cmd-journey":
            assert all(title in screen for title in ("SHOW", "FIND", "DEPS", "IMPACT", "Controller")), "Palette journey missing a result"
            assert screen.count("FIND") >= 3, "Palette next page did not run: " + "\n".join(row.rstrip() for row in screen.splitlines() if row.strip())[-3000:]
            assert "INVALID_USAGE" not in screen and "OPERATION_FAILED" not in screen and "REFERENCE_UNAVAILABLE" not in screen
        separators = [row.rstrip() for row in screen.splitlines() if row.rstrip() and set(row.rstrip()) == {"─"}]
        assert separators and all(len(row) == width for row in separators), f"{name}: window width not respected"
        for row in screen.splitlines():
            assert len(row.rstrip()) <= width, f"{name}: content exceeded window width"
        colors = {cell.attributes & 15 for cell in cells if cell.char.UnicodeChar == "█"}
        assert len(colors) >= 3, f"{name}: logo colors not rendered ({len(colors)} distinct attributes)"
        mode = w.DWORD()
        expected_mode = shell_mode.value if name.startswith("cmd-") else 3
        assert kernel.GetConsoleMode(handle, ctypes.byref(mode)) and mode.value == expected_mode, f"{name}: console mode not restored (actual={mode.value}, expected={expected_mode})"
        assert kernel.GetConsoleOutputCP() == page and kernel.GetConsoleCP() == page, f"{name}: code page not restored"
        results.append({"case": name, "vtInitiallyOff": True, "rawAnsi": False, "width": width, "logoColors": len(colors), "restoredMode": mode.value, "restored": True})
    machine = subprocess.run([java, "-jar", str(root / "cli/target/evo.jar"), "--json", "--color", "always", "--version"],
                             cwd=root, env=environment, capture_output=True, timeout=15, check=True)
    assert b"\x1b" not in machine.stdout and not machine.stderr, "Machine output contaminated"
    assert json.loads(machine.stdout)["data"]["version"].startswith("evo ")
    results.append({"case": "redirected-json", "ansi": False, "validJson": True})
    journey_workspace.cleanup()
    print(json.dumps({"result": "PASS", "cases": results}))


def main():
    if sys.platform != "win32":
        print("SKIP: native Windows console required")
        return 0
    root = Path(__file__).resolve().parents[4]
    if "--worker" in sys.argv:
        try:
            worker(root)
            return 0
        except (AssertionError, OSError, subprocess.TimeoutExpired) as failure:
            print(json.dumps({"result": "FAIL", "reason": str(failure)}))
            return 1
    startup = subprocess.STARTUPINFO()
    startup.dwFlags |= subprocess.STARTF_USESHOWWINDOW
    startup.wShowWindow = 0
    result = subprocess.run([sys.executable, str(Path(__file__).resolve()), "--worker"],
                            creationflags=subprocess.CREATE_NEW_CONSOLE, startupinfo=startup,
                            capture_output=True, text=True, timeout=60)
    print(result.stdout.strip())
    if result.returncode and result.stderr:
        print(result.stderr[-1200:])
    return result.returncode


if __name__ == "__main__":
    sys.exit(main())
