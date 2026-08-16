package dev.vitorsilverio.virtualarmbox.device.bcm2835;

import dev.vitorsilverio.armjitter.memory.PagedAddressSpace;
import dev.vitorsilverio.virtualarmbox.device.OpenBus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Testa o roteamento por janela do {@link Bcm2835ArmControlBlock} — IC em `+0x200`, mailboxes
/// em `+0x800` (ver Javadoc da classe sobre por que os dois compartilham uma única página).
class Bcm2835ArmControlBlockTest {
    private static final int IC_IRQ_ENABLE_1 = 0x200 + 0x10;
    private static final int IC_IRQ_PENDING_1 = 0x200 + 0x04;
    private static final int MBOX0_STATUS = 0x800 + 0x98;
    private static final int ARM_MS_EMPTY = 0x4000_0000;

    @Test
    void routesIcWindowToIcOffsetZero() {
        Bcm2835Ic ic = new Bcm2835Ic();
        Bcm2835ArmControlBlock block = new Bcm2835ArmControlBlock(ic, new Bcm2835Mailbox(OpenBus.INSTANCE));

        block.write32(IC_IRQ_ENABLE_1, 1 << 5);
        ic.setGpuIrqLine(5, true);

        assertEquals(1 << 5, block.read32(IC_IRQ_PENDING_1));
        assertEquals(1 << 5, ic.read32(0x04), "o mesmo estado deve estar visível direto no IC");
    }

    @Test
    void routesMailboxWindowUnshifted() {
        PagedAddressSpace ram = new PagedAddressSpace(12, OpenBus.INSTANCE);
        ram.mapRam(0, new byte[4096]);
        Bcm2835ArmControlBlock block = new Bcm2835ArmControlBlock(new Bcm2835Ic(), new Bcm2835Mailbox(ram));

        assertEquals(ARM_MS_EMPTY, block.read32(MBOX0_STATUS));
    }

    @Test
    void outsideBothWindowsReadsZero() {
        Bcm2835ArmControlBlock block =
                new Bcm2835ArmControlBlock(new Bcm2835Ic(), new Bcm2835Mailbox(OpenBus.INSTANCE));
        assertTrue(block.read32(0x600) == 0, "fora das duas janelas conhecidas: sem efeito");
    }
}
