package dev.vitorsilverio.virtualarmbox.device.bcm2835;

import dev.vitorsilverio.armjitter.memory.PagedAddressSpace;
import dev.vitorsilverio.virtualarmbox.device.OpenBus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Testes do mailbox + canal de propriedades mínimo do BCM2835 — formato de buffer transcrito
/// de `hw/misc/bcm2835_property.c` do QEMU (`Mailbox-property-interface`), simplificado para
/// resposta síncrona (ver Javadoc de {@link Bcm2835Mailbox}).
class Bcm2835MailboxTest {
    private static final int PAGE_SHIFT = 12;
    private static final int MAIL0_STATUS = 0x98;
    private static final int MAIL0_CONFIG = 0x9C;
    private static final int MAIL1_WRITE = 0xA0;
    private static final int ARM_MS_EMPTY = 0x4000_0000;
    private static final int ARM_MC_IHAVEDATAIRQEN = 1;

    private static final int CHANNEL_PROPERTY = 8;
    private static final int BUFFER_ADDRESS = 0x1000; // alinhado a 16 bytes (canal nos bits baixos).
    private static final int RESPONSE_FLAG = 1 << 31;

    private static PagedAddressSpace newRam() {
        PagedAddressSpace ram = new PagedAddressSpace(PAGE_SHIFT, OpenBus.INSTANCE);
        ram.mapRam(0, new byte[64 * 1024]);
        return ram;
    }

    @Test
    void mail0StartsEmpty() {
        PagedAddressSpace ram = newRam();
        Bcm2835Mailbox mailbox = new Bcm2835Mailbox(ram);
        assertEquals(ARM_MS_EMPTY, mailbox.read32(MAIL0_STATUS));
    }

    @Test
    void unknownTagRespondsZeroedWithSuccessBit() {
        PagedAddressSpace ram = newRam();
        Bcm2835Mailbox mailbox = new Bcm2835Mailbox(ram);

        // Buffer: [0]=tamanho total, [4]=código, [8]=tag, [12]=bufsize, [16]=resp, [20..]=valor,
        // [24]=END_TAG. Uma tag desconhecida (0x99999999) com bufsize=4.
        int tagBase = BUFFER_ADDRESS + 8;
        ram.write32(BUFFER_ADDRESS, 28); // tamanho total do buffer.
        ram.write32(BUFFER_ADDRESS + 4, 0);
        ram.write32(tagBase, 0x9999_9999);
        ram.write32(tagBase + 4, 4); // bufsize
        ram.write32(tagBase + 8, 0); // código de requisição.
        ram.write32(tagBase + 12, 0xDEADBEEF); // valor de entrada, deve virar 0.
        ram.write32(tagBase + 16, 0); // END_TAG.

        mailbox.write32(MAIL1_WRITE, BUFFER_ADDRESS | CHANNEL_PROPERTY);

        assertEquals(RESPONSE_FLAG | 4, ram.read32(tagBase + 8), "tag deveria responder sucesso+tamanho");
        assertEquals(0, ram.read32(tagBase + 12), "valor da tag desconhecida deveria ser zerado");
        assertEquals(RESPONSE_FLAG, ram.read32(BUFFER_ADDRESS + 4), "cabeçalho deveria marcar sucesso");
    }

    @Test
    void arm1WriteImmediatelyBecomesAvailableInMail0() {
        PagedAddressSpace ram = newRam();
        Bcm2835Mailbox mailbox = new Bcm2835Mailbox(ram);
        ram.write32(BUFFER_ADDRESS, 12); // buffer mínimo: só END_TAG.
        ram.write32(BUFFER_ADDRESS + 8, 0);

        mailbox.write32(MAIL1_WRITE, BUFFER_ADDRESS | CHANNEL_PROPERTY);

        assertFalse((mailbox.read32(MAIL0_STATUS) & ARM_MS_EMPTY) != 0, "mail0 deveria ter uma resposta pronta");
    }

    @Test
    void irqOnlyAssertedWhenEnabledInConfig() {
        PagedAddressSpace ram = newRam();
        Bcm2835Mailbox mailbox = new Bcm2835Mailbox(ram);
        ram.write32(BUFFER_ADDRESS, 12);
        ram.write32(BUFFER_ADDRESS + 8, 0);

        mailbox.write32(MAIL1_WRITE, BUFFER_ADDRESS | CHANNEL_PROPERTY);
        assertFalse(mailbox.irqAsserted(), "IRQ desabilitada por padrão (ARM_MC_IHAVEDATAIRQEN=0)");

        mailbox.write32(MAIL0_CONFIG, ARM_MC_IHAVEDATAIRQEN);
        assertTrue(mailbox.irqAsserted());
    }
}
