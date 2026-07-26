package dev.vitorsilverio.linuxbox.boot;

import dev.vitorsilverio.armjitter.memory.PagedAddressSpace;
import dev.vitorsilverio.linuxbox.device.OpenBus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Testes de `AtagsBuilder` (B4.1.5) contra o layout ATAG real (`include/asm/setup.h` do
/// kernel Linux) que `vmlinuz-3.2.0-4-versatile` espera em `r2`.
class AtagsBuilderTest {
    private static final int ATAG_CORE = 0x54410001;
    private static final int ATAG_MEM = 0x54410002;
    private static final int ATAG_INITRD2 = 0x54420005;
    private static final int ATAG_CMDLINE = 0x54410009;
    private static final int ATAG_NONE = 0x00000000;
    private static final int BASE = 0x100;
    private static final int PAGE_SHIFT = 12;

    private PagedAddressSpace newRam() {
        PagedAddressSpace ram = new PagedAddressSpace(PAGE_SHIFT, OpenBus.INSTANCE);
        ram.mapRam(0, new byte[1 << PAGE_SHIFT]);
        return ram;
    }

    @Test
    void coreTagHasFiveWordsAndCorrectId() {
        PagedAddressSpace ram = newRam();
        new AtagsBuilder(ram, BASE).core().end();
        assertEquals(5, ram.read32(BASE));
        assertEquals(ATAG_CORE, ram.read32(BASE + 4));
    }

    @Test
    void memTagCarriesSizeAndStart() {
        PagedAddressSpace ram = newRam();
        new AtagsBuilder(ram, BASE).core().memory(0x08000000, 0).end();
        int memTagOffset = BASE + 5 * 4; // após ATAG_CORE (5 palavras)
        assertEquals(4, ram.read32(memTagOffset));
        assertEquals(ATAG_MEM, ram.read32(memTagOffset + 4));
        assertEquals(0x08000000, ram.read32(memTagOffset + 8));
        assertEquals(0, ram.read32(memTagOffset + 12));
    }

    @Test
    void initrdTagCarriesStartAndSize() {
        PagedAddressSpace ram = newRam();
        new AtagsBuilder(ram, BASE).core().initrd(0x04000000, 12345).end();
        int initrdTagOffset = BASE + 5 * 4;
        assertEquals(4, ram.read32(initrdTagOffset));
        assertEquals(ATAG_INITRD2, ram.read32(initrdTagOffset + 4));
        assertEquals(0x04000000, ram.read32(initrdTagOffset + 8));
        assertEquals(12345, ram.read32(initrdTagOffset + 12));
    }

    @Test
    void cmdlineTagIsNulTerminatedAndWordAligned() {
        PagedAddressSpace ram = newRam();
        String cmdline = "console=ttyAMA0"; // 15 bytes -> +1 NUL = 16 bytes = 4 palavras exatas
        new AtagsBuilder(ram, BASE).core().cmdline(cmdline).end();
        int cmdlineTagOffset = BASE + 5 * 4;
        int sizeWords = ram.read32(cmdlineTagOffset);
        assertEquals(2 + 4, sizeWords);
        assertEquals(ATAG_CMDLINE, ram.read32(cmdlineTagOffset + 4));
        byte[] payload = new byte[16];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ram.read8(cmdlineTagOffset + 8 + i);
        }
        String read = new String(payload, 0, cmdline.length(), StandardCharsets.US_ASCII);
        assertEquals(cmdline, read);
        assertEquals(0, payload[cmdline.length()], "terminador NUL");
    }

    @Test
    void listEndsWithAtagNone() {
        PagedAddressSpace ram = newRam();
        new AtagsBuilder(ram, BASE).core().end();
        int noneTagOffset = BASE + 5 * 4;
        assertEquals(0, ram.read32(noneTagOffset));
        assertEquals(ATAG_NONE, ram.read32(noneTagOffset + 4));
    }

    @Test
    void fullSequenceMatchesQemuBootCOrdering() {
        // Ordem espelhando hw/arm/boot.c do QEMU: CORE -> MEM -> INITRD2 -> CMDLINE -> NONE.
        PagedAddressSpace ram = newRam();
        new AtagsBuilder(ram, BASE)
                .core()
                .memory(0x08000000, 0)
                .initrd(0x04000000, 100)
                .cmdline("x")
                .end();
        int offset = BASE;
        assertEquals(ATAG_CORE, ram.read32(offset + 4));
        offset += 5 * 4;
        assertEquals(ATAG_MEM, ram.read32(offset + 4));
        offset += 4 * 4;
        assertEquals(ATAG_INITRD2, ram.read32(offset + 4));
        offset += 4 * 4;
        assertEquals(ATAG_CMDLINE, ram.read32(offset + 4));
        int cmdlineWords = ram.read32(offset);
        offset += cmdlineWords * 4;
        assertEquals(ATAG_NONE, ram.read32(offset + 4));
    }
}
