package dev.vitorsilverio.virtualarmbox.device.bcm2835;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

/// "ARM control block" do BCM2835 (`ARM_OFFSET = 0xB000` em `raspi_platform.h` do QEMU,
/// físico `0x2000B000`): uma ÚNICA página de 4KiB que reúne o controlador de interrupção
/// (`ARMCTRL_IC_OFFSET = ARM_OFFSET+0x200`) e as mailboxes (`ARMCTRL_0_SBM_OFFSET =
/// ARM_OFFSET+0x800`, onde `MAIL0_READ` cai em `0x2000B880` — o endereço citado na maioria da
/// documentação). Existe só porque {@link Bcm2835Ic}/{@link Bcm2835Mailbox} não começam num
/// limite de página de 4KiB cada um (diferente do `versatilepb`, onde cada periférico já tinha
/// sua própria página): esta classe é o único ponto mapeado no barramento físico
/// ({@code PagedAddressSpace} exige alinhamento de página para {@code mapHandler}) e delega
/// pelo deslocamento dentro da página.
public final class Bcm2835ArmControlBlock implements AddressSpace {
    public static final int REGION_SIZE = 0x1000;

    private static final int IC_WINDOW_START = 0x200;
    private static final int IC_WINDOW_END = 0x400;
    private static final int SBM_WINDOW_START = 0x800;
    private static final int SBM_WINDOW_END = 0xC00;

    private final Bcm2835Ic ic;
    private final Bcm2835Mailbox mailbox;

    public Bcm2835ArmControlBlock(Bcm2835Ic ic, Bcm2835Mailbox mailbox) {
        this.ic = ic;
        this.mailbox = mailbox;
    }

    @Override
    public int read8(int address) {
        return read32(address) & 0xFF;
    }

    @Override
    public int read16(int address) {
        return read32(address) & 0xFFFF;
    }

    @Override
    public int read32(int address) {
        int offset = address & (REGION_SIZE - 1);
        if (offset >= IC_WINDOW_START && offset < IC_WINDOW_END) {
            return ic.read32(offset - IC_WINDOW_START);
        }
        if (offset >= SBM_WINDOW_START && offset < SBM_WINDOW_END) {
            return mailbox.read32(offset);
        }
        return 0;
    }

    @Override
    public void write8(int address, int value) {
        write32(address, value);
    }

    @Override
    public void write16(int address, int value) {
        write32(address, value);
    }

    @Override
    public void write32(int address, int value) {
        int offset = address & (REGION_SIZE - 1);
        if (offset >= IC_WINDOW_START && offset < IC_WINDOW_END) {
            ic.write32(offset - IC_WINDOW_START, value);
        } else if (offset >= SBM_WINDOW_START && offset < SBM_WINDOW_END) {
            mailbox.write32(offset, value);
        }
    }

    @Override
    public int accessCycles(int address, int sizeBytes, MemoryAccessType type) {
        return 0;
    }

    @Override
    public boolean providesAccessCycles() {
        return false;
    }
}
