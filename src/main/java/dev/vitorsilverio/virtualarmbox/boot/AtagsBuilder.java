package dev.vitorsilverio.virtualarmbox.boot;

import dev.vitorsilverio.armjitter.memory.AddressSpace;

import java.nio.charset.StandardCharsets;

/// Monta uma lista de ATAGs (`Documentation/arch/arm/booting.rst`/`include/asm/setup.h` do
/// kernel Linux) na memória física do hospedeiro — o protocolo de boot que o kernel
/// `vmlinuz-3.2.0-4-versatile` (`testdata/`, board file `arch/arm/mach-versatile`, sem
/// Device Tree — ver `testdata/README.md`) espera em `r2` na entrada.
///
/// Cada ATAG é `{size_em_palavras_de_32_bits (inclui o cabeçalho), tag_id, ...payload}`.
/// Layout usado (ordem espelhando `hw/arm/boot.c` do QEMU, oráculo do épico B4.1):
/// `ATAG_CORE` → `ATAG_MEM` → `ATAG_INITRD2` (se houver initrd) → `ATAG_CMDLINE` →
/// `ATAG_NONE`.
public final class AtagsBuilder {
    private static final int ATAG_CORE = 0x54410001;
    private static final int ATAG_CORE_SIZE_WORDS = 5; // cabeçalho(2) + flags + pagesize + rootdev
    private static final int ATAG_MEM = 0x54410002;
    private static final int ATAG_MEM_SIZE_WORDS = 4; // cabeçalho(2) + size + start
    private static final int ATAG_INITRD2 = 0x54420005;
    private static final int ATAG_INITRD2_SIZE_WORDS = 4; // cabeçalho(2) + start + size
    private static final int ATAG_CMDLINE = 0x54410009;
    private static final int ATAG_NONE = 0x00000000;
    private static final int ATAG_NONE_SIZE_WORDS = 0;

    private static final int WORD_BYTES = 4;

    private final AddressSpace memory;
    private int cursor;

    /// @param memory barramento físico onde escrever a lista
    /// @param baseAddress endereço físico do início da lista (`r2` na entrada do kernel)
    public AtagsBuilder(AddressSpace memory, int baseAddress) {
        this.memory = memory;
        this.cursor = baseAddress;
    }

    /// Escreve `ATAG_CORE` (sem flags/pagesize/rootdev especiais — todos zero, forma "bare"
    /// estendida para o parser aceitar o tamanho maior sem exigi-lo).
    public AtagsBuilder core() {
        writeWord(ATAG_CORE_SIZE_WORDS);
        writeWord(ATAG_CORE);
        writeWord(0); // flags
        writeWord(0); // pagesize
        writeWord(0); // rootdev
        return this;
    }

    /// Escreve `ATAG_MEM`: um bloco de RAM `[start, start+size)`.
    public AtagsBuilder memory(int size, int start) {
        writeWord(ATAG_MEM_SIZE_WORDS);
        writeWord(ATAG_MEM);
        writeWord(size);
        writeWord(start);
        return this;
    }

    /// Escreve `ATAG_INITRD2`: endereço físico + tamanho do initramfs já carregado.
    public AtagsBuilder initrd(int start, int size) {
        writeWord(ATAG_INITRD2_SIZE_WORDS);
        writeWord(ATAG_INITRD2);
        writeWord(start);
        writeWord(size);
        return this;
    }

    /// Escreve `ATAG_CMDLINE`: string terminada em NUL, alinhada a 4 bytes (o tamanho do
    /// ATAG inclui o padding).
    public AtagsBuilder cmdline(String cmdline) {
        byte[] bytes = cmdline.getBytes(StandardCharsets.US_ASCII);
        int payloadBytes = bytes.length + 1; // + NUL
        int payloadWords = (payloadBytes + WORD_BYTES - 1) / WORD_BYTES;
        writeWord(2 + payloadWords);
        writeWord(ATAG_CMDLINE);
        int written = 0;
        for (byte b : bytes) {
            memory.write8(cursor++, b);
            written++;
        }
        while (written < payloadWords * WORD_BYTES) {
            memory.write8(cursor++, 0);
            written++;
        }
        return this;
    }

    /// Escreve `ATAG_NONE`, fechando a lista.
    public void end() {
        writeWord(ATAG_NONE_SIZE_WORDS);
        writeWord(ATAG_NONE);
    }

    private void writeWord(int value) {
        memory.write32(cursor, value);
        cursor += WORD_BYTES;
    }
}
