package dev.vitorsilverio.virtualarmbox.device;

import dev.vitorsilverio.armjitter.memory.AddressSpace;

/// Barramento aberto: qualquer endereço sem RAM nem periférico mapeado cai aqui. Leituras
/// devolvem `0` (não há como modelar todo periférico real do `versatilepb` — SIC, GPIO,
/// RTC, MMCI, AACI, CLCD, DMAC, watchdog, PCI, Ethernet — e isso é intencional: nenhum
/// deles está no "Inclui" da task B4.1.5; os drivers correspondentes no kernel falham a
/// sondagem [`amba_match`/`probe`] normalmente, como aconteceria com hardware real não
/// populado numa placa, sem interromper o boot) e escritas são ignoradas.
public final class OpenBus implements AddressSpace {
    public static final OpenBus INSTANCE = new OpenBus();

    private OpenBus() {
    }

    @Override
    public int read8(int address) {
        return 0;
    }

    @Override
    public int read16(int address) {
        return 0;
    }

    @Override
    public int read32(int address) {
        return 0;
    }

    @Override
    public void write8(int address, int value) {
    }

    @Override
    public void write16(int address, int value) {
    }

    @Override
    public void write32(int address, int value) {
    }

    @Override
    public boolean providesAccessCycles() {
        return false;
    }
}
