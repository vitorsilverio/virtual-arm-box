package dev.vitorsilverio.virtualarmbox.device.bcm2836;

import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import org.junit.jupiter.api.Test;

import static dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bcm2836GenericTimerTest {
    private static final long ENABLE = 1;
    private static final long IMASK = 2;
    private static final long ISTATUS = 4;

    @Test
    void handlesPhysicalAndVirtualRegistersOnly() {
        Bcm2836GenericTimer timer = new Bcm2836GenericTimer();
        for (Aarch64SystemRegisterId id : new Aarch64SystemRegisterId[] {CNTFRQ_EL0, CNTPCT_EL0, CNTP_TVAL_EL0,
                CNTP_CTL_EL0, CNTP_CVAL_EL0, CNTVCT_EL0, CNTV_TVAL_EL0, CNTV_CTL_EL0, CNTV_CVAL_EL0}) {
            assertTrue(timer.handles(id), id.name());
        }
        assertFalse(timer.handles(TTBR0_EL1));
    }

    @Test
    void frequencyIsTheRealPi3Crystal() {
        assertEquals(19_200_000L, new Bcm2836GenericTimer().read(CNTFRQ_EL0));
    }

    @Test
    void virtualCounterTracksThePhysicalOne() {
        Bcm2836GenericTimer timer = new Bcm2836GenericTimer();
        timer.advance(1234);
        assertEquals(1234, timer.read(CNTPCT_EL0));
        assertEquals(1234, timer.read(CNTVCT_EL0));
    }

    @Test
    void countersAndFrequencyIgnoreGuestWrites() {
        Bcm2836GenericTimer timer = new Bcm2836GenericTimer();
        timer.advance(10);
        timer.write(CNTFRQ_EL0, 1);
        timer.write(CNTPCT_EL0, 999);
        timer.write(CNTVCT_EL0, 999);
        assertEquals(19_200_000L, timer.read(CNTFRQ_EL0));
        assertEquals(10, timer.read(CNTPCT_EL0));
        assertEquals(10, timer.read(CNTVCT_EL0));
    }

    @Test
    void physicalComparatorFiresViaTval() {
        Bcm2836GenericTimer timer = new Bcm2836GenericTimer();
        timer.write(CNTP_TVAL_EL0, 100);
        timer.write(CNTP_CTL_EL0, ENABLE);
        assertFalse(timer.physicalTimerIrqPending());
        assertEquals(ENABLE, timer.read(CNTP_CTL_EL0));
        assertEquals(100, timer.read(CNTP_TVAL_EL0));
        timer.advance(100);
        assertTrue(timer.physicalTimerIrqPending());
        assertEquals(ENABLE | ISTATUS, timer.read(CNTP_CTL_EL0));
        assertEquals(100, timer.read(CNTP_CVAL_EL0));
        assertFalse(timer.virtualTimerIrqPending(), "comparadores são independentes");
    }

    @Test
    void virtualComparatorFiresViaCval() {
        Bcm2836GenericTimer timer = new Bcm2836GenericTimer();
        timer.write(CNTV_CVAL_EL0, 50);
        timer.write(CNTV_CTL_EL0, ENABLE);
        assertEquals(50, timer.read(CNTV_CVAL_EL0));
        assertFalse(timer.virtualTimerIrqPending());
        timer.advance(50);
        assertTrue(timer.virtualTimerIrqPending());
        assertEquals(ENABLE | ISTATUS, timer.read(CNTV_CTL_EL0));
        assertFalse(timer.physicalTimerIrqPending());
    }

    @Test
    void virtualTvalIsSignExtendedAndMasked() {
        Bcm2836GenericTimer timer = new Bcm2836GenericTimer();
        timer.advance(1000);
        timer.write(CNTV_TVAL_EL0, 0xFFFF_FFFFL); // -1: já expirou
        assertEquals(999, timer.read(CNTV_CVAL_EL0));
        timer.write(CNTV_CTL_EL0, ENABLE);
        assertTrue(timer.virtualTimerIrqPending());
        assertEquals(0xFFFF_FFFFL, timer.read(CNTV_TVAL_EL0));
    }

    @Test
    void maskedOrDisabledComparatorNeverRaisesTheLine() {
        Bcm2836GenericTimer timer = new Bcm2836GenericTimer();
        timer.write(CNTV_CVAL_EL0, 0);
        timer.write(CNTV_CTL_EL0, 0); // desabilitado, mas ISTATUS visível
        assertFalse(timer.virtualTimerIrqPending());
        assertEquals(ISTATUS, timer.read(CNTV_CTL_EL0));
        timer.write(CNTV_CTL_EL0, ENABLE | IMASK);
        assertFalse(timer.virtualTimerIrqPending());
        assertEquals(ENABLE | IMASK | ISTATUS, timer.read(CNTV_CTL_EL0));
        timer.write(CNTV_CTL_EL0, ENABLE | ISTATUS); // ISTATUS é RO: escrita ignorada
        assertTrue(timer.virtualTimerIrqPending());
    }

    @Test
    void unservedRegistersAreRejected() {
        Bcm2836GenericTimer timer = new Bcm2836GenericTimer();
        assertThrows(UnsupportedOperationException.class, () -> timer.read(TTBR0_EL1));
        assertThrows(UnsupportedOperationException.class, () -> timer.write(TTBR0_EL1, 0));
    }
}
