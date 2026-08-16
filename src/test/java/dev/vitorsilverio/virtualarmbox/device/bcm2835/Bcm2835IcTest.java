package dev.vitorsilverio.virtualarmbox.device.bcm2835;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Testes do controlador de interrupção do BCM2835 — offsets/semântica transcritos de
/// `hw/intc/bcm2835_ic.c` do QEMU (`ARMCTRL-IC`).
class Bcm2835IcTest {
    private static final int REG_IRQ_PENDING_BASIC = 0x00;
    private static final int REG_IRQ_PENDING_2 = 0x08;
    private static final int REG_FIQ_CONTROL = 0x0C;
    private static final int REG_IRQ_ENABLE_1 = 0x10;
    private static final int REG_IRQ_ENABLE_2 = 0x14;
    private static final int REG_IRQ_ENABLE_BASIC = 0x18;
    private static final int REG_IRQ_DISABLE_2 = 0x20;

    /// Fonte GPU nº 57 (`INTERRUPT_UART0` de `raspi_platform.h`) — bit 57 cai no registrador
    /// **2** (bits 32-63, `REG_IRQ_ENABLE_2`/`REG_IRQ_PENDING_2`), não no 1: `57-32=25`.
    private static final int GPU_SOURCE_UART0 = 57;
    private static final int GPU_SOURCE_UART0_BIT_IN_REG2 = 1 << (GPU_SOURCE_UART0 - 32);
    /// Fonte GPU nº 7, um dos "IRQ duplicados" nos bits 10-20 de `IRQ_PENDING_BASIC`.
    private static final int GPU_SOURCE_DUPLICATED = 7;
    private static final int ARM_SOURCE_MAILBOX = 1;

    @Test
    void gpuIrqNotAssertedUntilEnabled() {
        Bcm2835Ic ic = new Bcm2835Ic();
        ic.setGpuIrqLine(GPU_SOURCE_UART0, true);
        assertFalse(ic.irqAsserted(), "fonte GPU não habilitada não deveria acordar nIRQ");

        ic.write32(REG_IRQ_ENABLE_2, GPU_SOURCE_UART0_BIT_IN_REG2);
        assertTrue(ic.irqAsserted());
        assertEquals(GPU_SOURCE_UART0_BIT_IN_REG2, ic.read32(REG_IRQ_PENDING_2));
    }

    @Test
    void disablingGpuIrqClearsIt() {
        Bcm2835Ic ic = new Bcm2835Ic();
        ic.write32(REG_IRQ_ENABLE_2, GPU_SOURCE_UART0_BIT_IN_REG2);
        ic.setGpuIrqLine(GPU_SOURCE_UART0, true);
        assertTrue(ic.irqAsserted());

        ic.write32(REG_IRQ_DISABLE_2, GPU_SOURCE_UART0_BIT_IN_REG2);
        assertFalse(ic.irqAsserted());
    }

    @Test
    void armIrqIsIndependentOfGpuIrqs() {
        Bcm2835Ic ic = new Bcm2835Ic();
        ic.write32(REG_IRQ_ENABLE_BASIC, 1 << ARM_SOURCE_MAILBOX);
        ic.setArmIrqLine(ARM_SOURCE_MAILBOX, true);
        assertTrue(ic.irqAsserted());
        assertEquals(1 << ARM_SOURCE_MAILBOX, ic.read32(REG_IRQ_PENDING_BASIC) & 0xFF);
    }

    @Test
    void duplicatedGpuSourceAppearsInPendingBasicBits10to20() {
        Bcm2835Ic ic = new Bcm2835Ic();
        ic.write32(REG_IRQ_ENABLE_1, 1 << GPU_SOURCE_DUPLICATED);
        ic.setGpuIrqLine(GPU_SOURCE_DUPLICATED, true);
        // GPU_SOURCE_DUPLICATED=7 é o primeiro elemento de irq_dups -> bit 10.
        assertTrue((ic.read32(REG_IRQ_PENDING_BASIC) & (1 << 10)) != 0);
    }

    @Test
    void fiqSelectedSourceAssertsFiqLine() {
        // Achado ao escrever este teste: nem `bcm2835_ic_update` nem `bcm2835_ic_read` do QEMU
        // excluem a fonte selecionada para FIQ da linha `nIRQ`/dos registradores `IRQ_PENDING_*`
        // (nenhum dos dois cálculos usa `& ~fiq_select`). FIQ é um sinal ADICIONAL, não um
        // desvio mutuamente exclusivo — quem decide qual vetor atender é o core (FIQ tem
        // prioridade sobre IRQ no modelo de exceção ARM), não o controlador.
        Bcm2835Ic ic = new Bcm2835Ic();
        ic.write32(REG_IRQ_ENABLE_2, GPU_SOURCE_UART0_BIT_IN_REG2);
        ic.write32(REG_FIQ_CONTROL, GPU_SOURCE_UART0 | (1 << 7)); // seleciona + habilita FIQ.
        ic.setGpuIrqLine(GPU_SOURCE_UART0, true);

        assertTrue(ic.fiqAsserted());
    }
}
