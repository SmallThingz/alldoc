"""Validate packaged Android ELF artifacts without executing them."""
import struct


def verify_elf(path, abi, executable):
    data = path.read_bytes()
    machine = {"arm64-v8a": 183, "x86_64": 62}[abi]
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<HH", data, 16) != (3, machine):
        raise RuntimeError(f"{path}: expected Android 64-bit ELF for {abi}")
    offset = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    if size != 56 or not count or offset + count * size > len(data):
        raise RuntimeError(f"{path}: invalid ELF program headers")
    loads = 0
    interpreter = None
    for index in range(count):
        kind, flags, file_offset, address, _, file_size, memory_size, alignment = struct.unpack_from("<IIQQQQQQ", data, offset + index * size)
        if file_offset + file_size > len(data):
            raise RuntimeError(f"{path}: segment extends beyond artifact")
        if kind == 1:
            loads += 1
            if alignment < 16384 or alignment & (alignment - 1) or address % alignment != file_offset % alignment or file_size > memory_size:
                raise RuntimeError(f"{path}: invalid or non-16-KiB ELF load alignment")
        elif kind == 3:
            interpreter = data[file_offset:file_offset + file_size]
        elif kind == 0x6474e551 and flags & 1:
            raise RuntimeError(f"{path}: executable native stack")
    if not loads or (interpreter != b"/system/bin/linker64\0" if executable else interpreter is not None):
        raise RuntimeError(f"{path}: unexpected Android ELF executable/shared-library type")
