package dev.vitorsilverio.linuxbox.device;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Testes do PL190 (B4.1.5) — offsets/semântica transcritos de `hw/intc/pl190.c` do QEMU.
class Pl190VicTest {
    private static final int REG_INTENABLE = 0x10;
    private static final int REG_SOFTINT = 0x18;
    private static final int REG_SOFTINTCLEAR = 0x1C;
    private static final int ID_REGION_START = 0xFE0;

    @Test
    void unmaskedSourceRaisesIrqLine() {
        Pl190Vic vic = new Pl190Vic();
        assertFalse(vic.irqAsserted());
        vic.write32(REG_INTENABLE, 1 << 4);
        vic.setIrqLine(4, true);
        assertTrue(vic.irqAsserted());
        vic.setIrqLine(4, false);
        assertFalse(vic.irqAsserted());
    }

    @Test
    void maskedSourceDoesNotRaiseIrqLine() {
        Pl190Vic vic = new Pl190Vic();
        vic.setIrqLine(12, true); // fonte nunca habilitada em INTENABLE
        assertFalse(vic.irqAsserted());
    }

    @Test
    void softwareInterruptBehavesLikeSource0() {
        Pl190Vic vic = new Pl190Vic();
        vic.write32(REG_INTENABLE, 1);
        vic.write32(REG_SOFTINT, 1);
        assertTrue(vic.irqAsserted());
        vic.write32(REG_SOFTINTCLEAR, 1);
        assertFalse(vic.irqAsserted());
    }

    @Test
    void multipleSourcesCombineAsLogicalOr() {
        Pl190Vic vic = new Pl190Vic();
        vic.write32(REG_INTENABLE, (1 << 4) | (1 << 12));
        vic.setIrqLine(4, true);
        assertTrue(vic.irqAsserted());
        vic.setIrqLine(4, false);
        assertFalse(vic.irqAsserted());
        vic.setIrqLine(12, true);
        assertTrue(vic.irqAsserted());
    }

    @Test
    void primecellIdMatchesPl190() {
        Pl190Vic vic = new Pl190Vic();
        int[] expected = {0x90, 0x11, 0x04, 0x00, 0x0D, 0xf0, 0x05, 0xb1};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], vic.read32(ID_REGION_START + i * 4), "byte de ID " + i);
        }
    }
}
