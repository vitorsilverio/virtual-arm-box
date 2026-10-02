package dev.vitorsilverio.virtualarmbox.device.bcm2836;

import org.junit.jupiter.api.Test;

import static dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bcm2836LocalIntcTest {
    private static final int TIMER_CONTROL = 0x40;
    private static final int IRQ_SOURCE = 0x60;
    private static final int CNTPNSIRQ = 1 << 1;
    private static final int CNTVIRQ = 1 << 3;
    private static final int GPU_IRQ = 1 << 8;

    private final Bcm2836GenericTimer timer = new Bcm2836GenericTimer();
    private final Bcm2836LocalIntc intc = new Bcm2836LocalIntc(timer);

    private void expireVirtual() {
        timer.write(CNTV_CVAL_EL0, 0);
        timer.write(CNTV_CTL_EL0, 1);
    }

    private void expirePhysical() {
        timer.write(CNTP_CVAL_EL0, 0);
        timer.write(CNTP_CTL_EL0, 1);
    }

    @Test
    void virtualTimerNeedsItsEnableBitInTheControlRegister() {
        expireVirtual();
        assertFalse(intc.irqAsserted(), "timer expirado mas não habilitado no l1-intc");
        assertEquals(0, intc.read32(IRQ_SOURCE));
        intc.write32(TIMER_CONTROL, CNTVIRQ);
        assertTrue(intc.irqAsserted());
        assertEquals(CNTVIRQ, intc.read32(IRQ_SOURCE));
        assertEquals(CNTVIRQ, intc.read32(TIMER_CONTROL));
    }

    @Test
    void physicalTimerStillRoutesThroughItsOwnBit() {
        expirePhysical();
        intc.write32(TIMER_CONTROL, CNTVIRQ); // bit errado
        assertFalse(intc.irqAsserted());
        intc.write32(TIMER_CONTROL, CNTPNSIRQ);
        assertTrue(intc.irqAsserted());
        assertEquals(CNTPNSIRQ, intc.read32(IRQ_SOURCE));
    }

    @Test
    void enabledSourceWithoutExpiredTimerStaysQuiet() {
        intc.write32(TIMER_CONTROL, CNTPNSIRQ | CNTVIRQ);
        assertFalse(intc.irqAsserted());
        assertEquals(0, intc.read32(IRQ_SOURCE));
    }

    @Test
    void bothTimersReportTheirOwnSourceBits() {
        expirePhysical();
        expireVirtual();
        intc.write32(TIMER_CONTROL, CNTPNSIRQ | CNTVIRQ);
        assertEquals(CNTPNSIRQ | CNTVIRQ, intc.read32(IRQ_SOURCE));
    }

    @Test
    void legacyLinePassesThroughAsGpuIrq() {
        assertFalse(intc.irqAsserted());
        intc.setLegacyIcIrqLine(true);
        assertTrue(intc.irqAsserted());
        assertEquals(GPU_IRQ, intc.read32(IRQ_SOURCE));
    }

    @Test
    void narrowAccessorsAndUnknownRegisters() {
        intc.write8(TIMER_CONTROL, CNTVIRQ);
        assertEquals(CNTVIRQ, intc.read8(TIMER_CONTROL));
        intc.write16(TIMER_CONTROL, CNTPNSIRQ);
        assertEquals(CNTPNSIRQ, intc.read16(TIMER_CONTROL));
        intc.write32(IRQ_SOURCE, 0xFF); // RO: ignorado
        intc.write32(0x44, 0xFF);       // fora do subconjunto
        assertEquals(0, intc.read32(0x44));
        assertEquals(0, intc.accessCycles(0, 4, null));
        assertFalse(intc.providesAccessCycles());
    }
}
