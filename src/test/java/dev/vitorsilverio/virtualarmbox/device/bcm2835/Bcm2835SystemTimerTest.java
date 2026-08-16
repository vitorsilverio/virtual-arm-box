package dev.vitorsilverio.virtualarmbox.device.bcm2835;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Testes do System Timer do BCM2835 — offsets/semântica transcritos de
/// `hw/timer/bcm2835_systmr.c` do QEMU.
class Bcm2835SystemTimerTest {
    private static final int REG_CTRL_STATUS = 0x00;
    private static final int REG_COUNTER_LOW = 0x04;
    private static final int REG_COMPARE0 = 0x0C;
    private static final int REG_COMPARE1 = 0x10;

    /// Ciclos suficientes para o contador livre alcançar qualquer valor de comparação usado
    /// nestes testes, somados por {@link Bcm2835SystemTimer#advance} (constante interna privada
    /// — não acoplamos o teste a ela).
    private static final long GENEROUS_CYCLES = 1_000_000;

    @Test
    void freeRunningCounterAdvancesWithCycles() {
        Bcm2835SystemTimer timer = new Bcm2835SystemTimer();
        int before = timer.read32(REG_COUNTER_LOW);
        timer.advance(GENEROUS_CYCLES);
        int after = timer.read32(REG_COUNTER_LOW);
        assertTrue(after > before, "contador livre deveria ter avançado");
    }

    @Test
    void compare0FiresIrqOnceCounterReachesValue() {
        Bcm2835SystemTimer timer = new Bcm2835SystemTimer();
        int target = timer.read32(REG_COUNTER_LOW) + 1000;
        timer.write32(REG_COMPARE0, target);
        assertFalse(timer.irqAsserted(0));
        timer.advance(GENEROUS_CYCLES);
        assertTrue(timer.irqAsserted(0), "comparador 0 deveria ter disparado IRQ");
    }

    @Test
    void ackingCtrlStatusClearsIrq() {
        Bcm2835SystemTimer timer = new Bcm2835SystemTimer();
        timer.write32(REG_COMPARE0, timer.read32(REG_COUNTER_LOW) + 100);
        timer.advance(GENEROUS_CYCLES);
        assertTrue(timer.irqAsserted(0));
        timer.write32(REG_CTRL_STATUS, 1); // bit 0 = comparador 0.
        assertFalse(timer.irqAsserted(0));
    }

    @Test
    void comparatorsAreIndependent() {
        Bcm2835SystemTimer timer = new Bcm2835SystemTimer();
        timer.write32(REG_COMPARE1, timer.read32(REG_COUNTER_LOW) + 100);
        timer.advance(GENEROUS_CYCLES);
        assertTrue(timer.irqAsserted(1));
        assertFalse(timer.irqAsserted(0), "comparador 0 não foi armado, não deveria disparar");
    }
}
