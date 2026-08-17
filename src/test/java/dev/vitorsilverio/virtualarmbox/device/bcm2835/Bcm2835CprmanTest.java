package dev.vitorsilverio.virtualarmbox.device.bcm2835;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/// Testa o {@link Bcm2835Cprman} mínimo — ver Javadoc da classe para o achado real de boot que
/// motivou a implementação (M3 da task F3: `CM_LOCK` sempre "travado" evita o `ETIMEDOUT`/
/// *deferred probe* do driver `clk-bcm2835` que atrasava o registro do `ttyAMA0` real).
class Bcm2835CprmanTest {
    private static final int CM_LOCK = 0x114;
    private static final int CM_LOCK_ALL_PLLS_LOCKED = 0b1_1111 << 8;
    private static final int CM_UARTCTL = 0x0F0;
    private static final int CM_UARTDIV = 0x0F4;

    @Test
    void cmLockAlwaysReportsAllPllsLocked() {
        Bcm2835Cprman cprman = new Bcm2835Cprman();
        assertEquals(CM_LOCK_ALL_PLLS_LOCKED, cprman.read32(CM_LOCK));
    }

    @Test
    void writesToCmLockAreIgnored() {
        Bcm2835Cprman cprman = new Bcm2835Cprman();
        cprman.write32(CM_LOCK, 0);
        assertEquals(CM_LOCK_ALL_PLLS_LOCKED, cprman.read32(CM_LOCK), "CM_LOCK e computado, nao armazenado");
    }

    @Test
    void uartClockRegistersAreSeededNonZeroToAvoidDivideByZero() {
        Bcm2835Cprman cprman = new Bcm2835Cprman();
        assertNotEquals(0, cprman.read32(CM_UARTCTL));
        assertNotEquals(0, cprman.read32(CM_UARTDIV));
    }

    @Test
    void otherRegistersRoundTripAsSimpleStorage() {
        Bcm2835Cprman cprman = new Bcm2835Cprman();
        cprman.write32(0x100, 0xDEADBEEF);
        assertEquals(0xDEADBEEF, cprman.read32(0x100));
    }

    @Test
    void narrowAccessorsDelegateToWord() {
        Bcm2835Cprman cprman = new Bcm2835Cprman();
        assertEquals(CM_LOCK_ALL_PLLS_LOCKED & 0xFF, cprman.read8(CM_LOCK));
        assertEquals(CM_LOCK_ALL_PLLS_LOCKED & 0xFFFF, cprman.read16(CM_LOCK));

        cprman.write16(0x200, 0x1234);
        assertEquals(0x1234, cprman.read32(0x200));
        cprman.write8(0x204, 0x56);
        assertEquals(0x56, cprman.read32(0x204));
    }
}
