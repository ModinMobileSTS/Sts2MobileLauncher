#!/usr/bin/env python3
"""Patch the Godot Android input pool leak in a staged template AAR.

Godot 4.5.1 obtains an InputEventRunnable before filtering unsupported mouse
button actions.  ACTION_BUTTON_PRESS/RELEASE therefore consume pool entries
without dispatching or recycling them.  This patch keeps the original handler
as a package-local implementation base and overlays a small handler that
rejects unsupported actions before the base method obtains a pooled runnable.
The gesture handler's private type references are rewritten to the base type so
its calls still dispatch virtually to the overlay.
"""

from __future__ import annotations

import argparse
import copy
import io
import os
import struct
import subprocess
import tempfile
import zipfile
from pathlib import Path

OLD_INTERNAL = b"org/godotengine/godot/input/GodotInputHandler"
BASE_INTERNAL = b"org/godotengine/godot/input/GodotInputHandlerBase"
HANDLER_ENTRY = "org/godotengine/godot/input/GodotInputHandler.class"
BASE_ENTRY = "org/godotengine/godot/input/GodotInputHandlerBase.class"
GESTURE_ENTRY = "org/godotengine/godot/input/GodotGestureHandler.class"
CLASSES_ENTRY = "classes.jar"

WRAPPER_SOURCE = """\
package org.godotengine.godot.input;

import android.content.Context;
import android.view.MotionEvent;

import org.godotengine.godot.Godot;

/** Avoids allocating a pooled event for mouse actions the base handler ignores. */
public class GodotInputHandler extends GodotInputHandlerBase {
    public GodotInputHandler(Context context, Godot godot) {
        super(context, godot);
    }

    @Override
    boolean handleMouseEvent(int eventAction, int buttonsMask, float x, float y,
            float deltaX, float deltaY, boolean doubleClick, boolean sourceMouseRelative,
            float pressure, float tiltX, float tiltY) {
        switch (eventAction) {
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_EXIT:
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_SCROLL:
                return super.handleMouseEvent(eventAction, buttonsMask, x, y, deltaX, deltaY,
                        doubleClick, sourceMouseRelative, pressure, tiltX, tiltY);
            default:
                return false;
        }
    }
}
"""


def _u2(data: bytes, offset: int) -> tuple[int, int]:
    if offset + 2 > len(data):
        raise ValueError("truncated class file")
    return struct.unpack_from(">H", data, offset)[0], offset + 2


def _rewrite_class_references(data: bytes) -> bytes:
    """Rewrite internal-name UTF-8 constants while preserving a class file."""
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("expected a JVM class file")

    offset = 8
    constant_pool_count, offset = _u2(data, offset)
    output = bytearray(data[:offset])
    index = 1
    while index < constant_pool_count:
        if offset >= len(data):
            raise ValueError("truncated constant pool")
        tag = data[offset]
        output.append(tag)
        offset += 1

        if tag == 1:  # CONSTANT_Utf8
            length, offset = _u2(data, offset)
            end = offset + length
            if end > len(data):
                raise ValueError("truncated UTF-8 constant")
            value = data[offset:end].replace(OLD_INTERNAL, BASE_INTERNAL)
            output.extend(struct.pack(">H", len(value)))
            output.extend(value)
            offset = end
        elif tag in (3, 4):  # Integer, Float
            output.extend(data[offset : offset + 4])
            offset += 4
        elif tag in (5, 6):  # Long, Double
            output.extend(data[offset : offset + 8])
            offset += 8
            index += 1
        elif tag in (7, 8, 16, 19, 20):  # Class, String, MethodType, modules
            output.extend(data[offset : offset + 2])
            offset += 2
        elif tag in (9, 10, 11, 12, 17, 18):  # refs, NameAndType, dynamic
            output.extend(data[offset : offset + 4])
            offset += 4
        elif tag == 15:  # MethodHandle
            output.extend(data[offset : offset + 3])
            offset += 3
        else:
            raise ValueError(f"unsupported constant-pool tag {tag}")
        index += 1

    if offset > len(data):
        raise ValueError("truncated class file")
    output.extend(data[offset:])
    return bytes(output)


def _copy_info(info: zipfile.ZipInfo, filename: str | None = None) -> zipfile.ZipInfo:
    copied = copy.copy(info)
    if filename is not None:
        copied.filename = filename
    return copied

def _validate_source_shape(handler: bytes, gesture: bytes) -> None:
    required_handler_tokens = (
        b"InputEventRunnable",
        b"setMouseEvent",
        b"dispatchInputEventRunnable",
        b"handleMouseEvent",
    )
    missing = [token.decode("ascii") for token in required_handler_tokens if token not in handler]
    if missing:
        raise ValueError(f"unsupported Godot input handler ABI; missing {', '.join(missing)}")
    if OLD_INTERNAL not in gesture:
        raise ValueError("unsupported Godot input ABI; gesture handler has no input-handler reference")


def _write_classes_jar(source: Path, destination: Path) -> None:
    with zipfile.ZipFile(source, "r") as src, zipfile.ZipFile(destination, "w") as dst:
        for info in src.infolist():
            name = info.filename
            value = src.read(name)
            if name == HANDLER_ENTRY:
                name = BASE_ENTRY
                value = _rewrite_class_references(value)
            elif name == GESTURE_ENTRY:
                value = _rewrite_class_references(value)
            dst.writestr(_copy_info(info, name), value)


def _compile_wrapper(classes_jar: Path, javac: Path, android_jar: Path, output: Path) -> None:
    output.mkdir(parents=True, exist_ok=True)
    source = output.parent / "GodotInputHandler.java"
    source.write_text(WRAPPER_SOURCE, encoding="utf-8")
    classpath = os.pathsep.join((str(classes_jar), str(android_jar)))
    command = [
        str(javac),
        "-source",
        "17",
        "-target",
        "17",
        "-Xlint:-options",
        "-cp",
        classpath,
        "-d",
        str(output),
        str(source),
    ]
    try:
        subprocess.run(command, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    except subprocess.CalledProcessError as exc:
        raise RuntimeError(
            "failed to compile the Godot input pool patch:\n"
            + (exc.stdout or "")
            + (exc.stderr or "")
        ) from exc

    wrapper = output / HANDLER_ENTRY
    if not wrapper.is_file():
        raise RuntimeError(f"javac did not produce {HANDLER_ENTRY}")


def _build_patched_classes_jar(source: Path, destination: Path, wrapper: Path) -> None:
    with zipfile.ZipFile(source, "r") as src, zipfile.ZipFile(destination, "w") as dst:
        class_info: zipfile.ZipInfo | None = None
        for info in src.infolist():
            if info.filename == BASE_ENTRY:
                class_info = info
            dst.writestr(info, src.read(info.filename))
        if class_info is None:
            raise ValueError(f"{BASE_ENTRY} missing from classes.jar")
        dst.writestr(_copy_info(class_info, HANDLER_ENTRY), wrapper.read_bytes())


def _validate_patched_aar(aar: Path) -> None:
    with zipfile.ZipFile(aar, "r") as src:
        try:
            classes = src.read(CLASSES_ENTRY)
        except KeyError as exc:
            raise ValueError("Godot AAR has no classes.jar") from exc
    with tempfile.TemporaryDirectory(prefix="sts2-godot-input-check-") as tmp:
        classes_path = Path(tmp) / "classes.jar"
        classes_path.write_bytes(classes)
        with zipfile.ZipFile(classes_path, "r") as jar:
            entries = set(jar.namelist())
            required = {HANDLER_ENTRY, BASE_ENTRY, GESTURE_ENTRY}
            missing = required - entries
            if missing:
                raise ValueError(f"patched classes.jar is missing: {sorted(missing)}")
            wrapper = jar.read(HANDLER_ENTRY)
            base = jar.read(BASE_ENTRY)
            gesture = jar.read(GESTURE_ENTRY)
            if any(OLD_INTERNAL in value.replace(BASE_INTERNAL, b"") for value in (base, gesture)):
                raise ValueError("patched handler base still contains the old handler type reference")
            if BASE_INTERNAL not in wrapper or BASE_INTERNAL not in gesture:
                raise ValueError("patched handler/base type references are incomplete")
            if b"InputEventRunnable" in wrapper or b"InputEventRunnable" in gesture:
                raise ValueError("the filtering wrapper must not obtain pooled input events")


def patch_aar(aar: Path, javac: Path | None, android_jar: Path | None, check: bool) -> None:
    if check:
        _validate_patched_aar(aar)
        return

    with zipfile.ZipFile(aar, "r") as src:
        classes = src.read(CLASSES_ENTRY)
        with zipfile.ZipFile(io.BytesIO(classes), "r") as jar:
            entries = set(jar.namelist())
            if BASE_ENTRY not in entries:
                if HANDLER_ENTRY not in entries or GESTURE_ENTRY not in entries:
                    raise ValueError("unsupported Godot AAR: expected handler and gesture classes")
                _validate_source_shape(jar.read(HANDLER_ENTRY), jar.read(GESTURE_ENTRY))

    if BASE_ENTRY in entries:
        _validate_patched_aar(aar)
        return
    if HANDLER_ENTRY not in entries or GESTURE_ENTRY not in entries:
        raise ValueError("unsupported Godot AAR: expected handler and gesture classes")
    if javac is None or android_jar is None:
        raise ValueError("--javac and --android-jar are required when patching")
    if not javac.is_file() or not os.access(javac, os.X_OK):
        raise ValueError(f"javac is not executable: {javac}")
    if not android_jar.is_file():
        raise ValueError(f"Android platform jar not found: {android_jar}")

    with tempfile.TemporaryDirectory(prefix="sts2-godot-input-patch-") as tmp_name:
        tmp = Path(tmp_name)
        original_classes = tmp / "original-classes.jar"
        base_classes = tmp / "base-classes.jar"
        wrapper_dir = tmp / "wrapper-classes"
        patched_classes = tmp / "patched-classes.jar"
        original_classes.write_bytes(classes)
        _write_classes_jar(original_classes, base_classes)
        _compile_wrapper(base_classes, javac, android_jar, wrapper_dir)
        _build_patched_classes_jar(base_classes, patched_classes, wrapper_dir / HANDLER_ENTRY)

        aar_tmp = tmp / "patched.aar"
        with zipfile.ZipFile(aar, "r") as src, zipfile.ZipFile(aar_tmp, "w") as dst:
            for info in src.infolist():
                value = patched_classes.read_bytes() if info.filename == CLASSES_ENTRY else src.read(info.filename)
                dst.writestr(info, value)
        os.replace(aar_tmp, aar)

    _validate_patched_aar(aar)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("aar", type=Path)
    parser.add_argument("--javac", type=Path)
    parser.add_argument("--android-jar", type=Path)
    parser.add_argument("--check", action="store_true", help="verify an already patched AAR")
    args = parser.parse_args()
    try:
        patch_aar(args.aar, args.javac, args.android_jar, args.check)
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile) as exc:
        parser.error(str(exc))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
