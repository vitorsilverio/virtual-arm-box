#!/usr/bin/env python3
"""Monta testdata/initramfs.cpio (formato "newc" do cpio, o que o kernel Linux espera de
um initramfs embutido/via -initrd) a partir de testdata/init + testdata/busybox-armv5l.

Sem `cpio` disponivel nesta maquina (Windows/MSYS2) -- este script escreve o formato
"newc" a mao (13 campos hexadecimais de 8 digitos + nome + padding a 4 bytes, entrada
TRAILER!!! final), documentado em `Documentation/driver-api/early-userspace/buffer-format.rst`
do kernel. Determinístico (ino/mtime fixos) para o artefato versionado ser reproduzível
byte-a-byte a partir dos mesmos arquivos de entrada.

Uso: `python3 make_initramfs.py` (roda de dentro de testdata/, sem argumentos).
"""
import os
import stat
import struct

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "initramfs.cpio")

MAGIC = b"070701"


def _pad4(n):
    return (4 - (n % 4)) % 4


def _header(ino, mode, filesize, namesize):
    # newc: magic + 13 campos hex de 8 digitos (ino,mode,uid,gid,nlink,mtime,filesize,
    # devmajor,devminor,rdevmajor,rdevminor,namesize,check).
    fields = [ino, mode, 0, 0, 1, 0, filesize, 0, 0, 0, 0, namesize, 0]
    return MAGIC + b"".join(b"%08X" % f for f in fields)


def _entry(name, mode, data, ino):
    name_bytes = name.encode("ascii") + b"\x00"
    header = _header(ino, mode, len(data), len(name_bytes))
    entry = header + name_bytes
    entry += b"\x00" * _pad4(len(entry))
    entry += data
    entry += b"\x00" * _pad4(len(data))
    return entry


def build():
    entries = []
    ino = 1

    def add_dir(name):
        nonlocal ino
        ino += 1
        entries.append(_entry(name, stat.S_IFDIR | 0o755, b"", ino))

    def add_file(name, path, mode):
        nonlocal ino
        ino += 1
        with open(path, "rb") as fh:
            data = fh.read()
        entries.append(_entry(name, stat.S_IFREG | mode, data, ino))

    add_dir("bin")
    add_dir("proc")
    add_dir("sys")
    add_file("bin/busybox", os.path.join(HERE, "busybox-armv5l"), 0o755)
    add_file("init", os.path.join(HERE, "init"), 0o755)

    ino += 1
    entries.append(_entry("TRAILER!!!", 0, b"", 0))

    with open(OUT, "wb") as fh:
        for e in entries:
            fh.write(e)
    print(f"wrote {OUT} ({os.path.getsize(OUT)} bytes)")


if __name__ == "__main__":
    build()
