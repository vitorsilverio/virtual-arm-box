package dev.vitorsilverio.linuxbox.device;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Testes do SP804 (B4.1.5) — offsets/semântica transcritos de `hw/timer/arm_timer.c` do QEMU
/// (`arm_timer_state`/`SP804State`).
class Sp804DualTimerTest {
    private static final int REG_LOAD = 0x00;
    private static final int REG_VALUE = 0x04;
    private static final int REG_CONTROL = 0x08;
    private static final int REG_INTCLR = 0x0C;
    private static final int REG_RIS = 0x10;
    private static final int TIMER1_OFFSET = 0x20;
    private static final int ID_REGION_START = 0xFE0;

    private static final int CTRL_32BIT = 1 << 1;
    private static final int CTRL_IE = 1 << 5;
    private static final int CTRL_PERIODIC = 1 << 6;
    private static final int CTRL_ENABLE = 1 << 7;

    /// Ticks suficientes para disparar um temporizador de recarga 100 quando somados aos ticks
    /// acumulados por {@link Sp804DualTimer#HOST_CYCLES_PER_TIMER_TICK} (privado — usamos ciclos
    /// de sobra, não o valor exato, para não acoplar o teste à constante interna).
    private static final long GENEROUS_CYCLES = 100_000;

    @Test
    void timer0PeriodicFiresInterruptAfterLoadTicksElapse() {
        Sp804DualTimer timer = new Sp804DualTimer();
        timer.write32(REG_CONTROL, CTRL_IE | CTRL_32BIT | CTRL_PERIODIC);
        timer.write32(REG_LOAD, 100);
        timer.write32(REG_CONTROL, CTRL_IE | CTRL_32BIT | CTRL_PERIODIC | CTRL_ENABLE);
        assertFalse(timer.irqAsserted());
        timer.advance(GENEROUS_CYCLES);
        assertTrue(timer.irqAsserted(), "temporizador periódico deveria ter disparado IRQ");
        assertEquals(1, timer.read32(REG_RIS));
        timer.write32(REG_INTCLR, 1);
        assertFalse(timer.irqAsserted());
    }

    @Test
    void timer1IsIndependentOfTimer0() {
        Sp804DualTimer timer = new Sp804DualTimer();
        timer.write32(TIMER1_OFFSET + REG_CONTROL, CTRL_IE | CTRL_32BIT | CTRL_PERIODIC);
        timer.write32(TIMER1_OFFSET + REG_LOAD, 50);
        timer.write32(TIMER1_OFFSET + REG_CONTROL, CTRL_IE | CTRL_32BIT | CTRL_PERIODIC | CTRL_ENABLE);
        timer.advance(GENEROUS_CYCLES);
        assertTrue(timer.irqAsserted());
        // timer0 nunca foi configurado/habilitado: não deveria ter valor de RIS setado.
        assertEquals(0, timer.read32(REG_RIS));
    }

    @Test
    void disabledTimerNeverFires() {
        Sp804DualTimer timer = new Sp804DualTimer();
        timer.write32(REG_LOAD, 10);
        timer.write32(REG_CONTROL, CTRL_IE | CTRL_32BIT | CTRL_PERIODIC); // sem CTRL_ENABLE
        timer.advance(GENEROUS_CYCLES);
        assertFalse(timer.irqAsserted());
    }

    @Test
    void primecellAndTimerIdMatchSp804() {
        Sp804DualTimer timer = new Sp804DualTimer();
        int[] expected = {0x04, 0x18, 0x14, 0x00, 0x0d, 0xf0, 0x05, 0xb1};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], timer.read32(ID_REGION_START + i * 4), "byte de ID " + i);
        }
    }
}
