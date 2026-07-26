package dev.vitorsilverio.linuxbox.device;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Testes do PL011 (B4.1.5) — offsets e semântica transcritos de `hw/char/pl011.c` do QEMU.
class Pl011UartTest {
    private static final int REG_DR = 0x00;
    private static final int REG_FR = 0x18;
    private static final int REG_IMSC = 0x38;
    private static final int REG_RIS = 0x3C;
    private static final int REG_ICR = 0x44;
    private static final int FLAG_RXFE = 0x10;
    private static final int FLAG_TXFE = 0x80;
    private static final int INT_TX = 1 << 5;
    private static final int INT_RX = 1 << 4;
    private static final int ID_REGION_START = 0xFE0;

    @Test
    void writingDataRegisterForwardsToConsoleOutput() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Pl011Uart uart = new Pl011Uart(out);
        uart.write32(REG_DR, 'A');
        uart.write32(REG_DR, 'B');
        assertEquals("AB", out.toString(StandardCharsets.US_ASCII));
    }

    @Test
    void flagRegisterStartsWithRxEmptyAndTxEmpty() {
        Pl011Uart uart = new Pl011Uart(new ByteArrayOutputStream());
        int flags = uart.read32(REG_FR);
        assertEquals(FLAG_RXFE | FLAG_TXFE, flags);
    }

    @Test
    void receiveByteClearsRxfeAndDataRegisterReturnsIt() {
        Pl011Uart uart = new Pl011Uart(new ByteArrayOutputStream());
        uart.receiveByte('Z');
        assertFalse((uart.read32(REG_FR) & FLAG_RXFE) != 0, "RXFE deveria cair após receiveByte");
        assertEquals('Z', uart.read32(REG_DR));
        assertTrue((uart.read32(REG_FR) & FLAG_RXFE) != 0, "RXFE deveria voltar após esvaziar o FIFO");
    }

    @Test
    void writeRaisesTxInterruptWhenEnabled() {
        Pl011Uart uart = new Pl011Uart(new ByteArrayOutputStream());
        uart.write32(REG_IMSC, INT_TX | INT_RX);
        assertFalse(uart.irqAsserted());
        uart.write32(REG_DR, 'X');
        assertTrue(uart.irqAsserted());
        assertEquals(INT_TX, uart.read32(REG_RIS) & INT_TX);
    }

    @Test
    void receiveRaisesRxInterruptWhenEnabledAndIcrClearsIt() {
        Pl011Uart uart = new Pl011Uart(new ByteArrayOutputStream());
        uart.write32(REG_IMSC, INT_RX);
        uart.receiveByte('Q');
        assertTrue(uart.irqAsserted());
        uart.write32(REG_ICR, INT_RX);
        assertFalse(uart.irqAsserted());
    }

    @Test
    void primecellIdMatchesArmVariant() {
        Pl011Uart uart = new Pl011Uart(new ByteArrayOutputStream());
        int[] expected = {0x11, 0x10, 0x14, 0x00, 0x0d, 0xf0, 0x05, 0xb1};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], uart.read32(ID_REGION_START + i * 4),
                    "byte de ID no índice " + i);
        }
    }
}
