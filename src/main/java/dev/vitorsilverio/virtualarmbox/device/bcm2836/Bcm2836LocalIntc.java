package dev.vitorsilverio.virtualarmbox.device.bcm2836;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

/// Controlador de interrupção LOCAL por-núcleo do BCM2836/2837 (`brcm,bcm2836-l1-intc`,
/// endereço físico `0x4000_0000` — **NÃO** faz parte do barramento de periféricos `0x7Exxxxxx`/
/// `0x3Fxxxxxx` como o `Bcm2835Ic` legado; é uma janela de endereço FÍSICO separada, própria do
/// BCM2836+, `QA7_rev3.4.pdf`/documentação pública do bloco "ARM local peripherals"). Task F11:
/// roteia o PPI do timer genérico ARM ({@link Bcm2836GenericTimer#physicalTimerIrqPending()})
/// para o core e faz passthrough da linha `nIRQ` combinada do controlador legado
/// (`Bcm2835Ic`, reaproveitado sem alteração — GPU/UART/mailbox continuam por ele), já que num
/// hospedeiro de 1 núcleo o roteamento de GPU IRQ é sempre para o core 0 (registrador
/// `GPU_INT_ROUTING`, `+0x0C`, não implementado — sempre core 0 aqui, mesma simplificação
/// "1 núcleo" do resto da task).
///
/// Subconjunto MÍNIMO coberto (só o necessário para `drivers/irqchip/irq-bcm2836.c` funcionar
/// com 1 núcleo e o timer genérico): `Core0 Timer Interrupt Control` (`+0x40`, habilita/
/// desabilita as 4 PPIs de timer por bit) e `Core0 IRQ Source` (`+0x60`, RO — bits pendentes).
/// FIQ (`Core0 FIQ Control`, `+0x70`) e os demais 3 núcleos (`+0x44..0x4C`/`+0x64..0x6C`) ficam
/// fora — sem consumidor real (1 núcleo só, sem FIQ usado por este boot).
public final class Bcm2836LocalIntc implements AddressSpace {
    /// Só os registradores até `+0x60` são usados (ver Javadoc da classe) — a página inteira é
    /// reservada por causa da granularidade fixa de {@code PagedAddressSpace} (4KiB, mesmo
    /// padrão de {@code Bcm2835SystemTimer#REGION_SIZE}/etc.).
    public static final int REGION_SIZE = 0x1000;

    private static final int REG_CORE0_TIMER_IRQ_CONTROL = 0x40;
    private static final int REG_CORE0_IRQ_SOURCE = 0x60;

    /// `nCNTPNSIRQ` (timer físico não-seguro) — bit `1` de `Core0 Timer Interrupt Control`/
    /// `Core0 IRQ Source` (`QA7_rev3.4.pdf` tabela "Timer & Mailbox Interrupt control"). É a
    /// ÚNICA fonte de timer habilitada por `arch_timer_starting_cpu` num boot EL1 sem
    /// hipervisor — `nCNTPSIRQ`(bit0)/`nCNTHPIRQ`(bit2)/`nCNTVIRQ`(bit3) ficam fora (sem
    /// consumidor, mesma disciplina de {@link Bcm2836GenericTimer}).
    private static final int TIMER_CONTROL_CNTPNSIRQ_BIT = 1 << 1;
    /// Bit `8` de `Core0 IRQ Source` — passthrough da linha `nIRQ` combinada do `Bcm2835Ic`
    /// legado (`QA7_rev3.4.pdf`: "GPU IRQ", sempre roteada ao core 0 nesta task).
    private static final int SOURCE_GPU_IRQ_BIT = 1 << 8;

    private final Bcm2836GenericTimer genericTimer;
    private int timerIrqControl;
    private boolean legacyIcIrqLine;

    public Bcm2836LocalIntc(Bcm2836GenericTimer genericTimer) {
        this.genericTimer = genericTimer;
    }

    /// Atualiza o passthrough da linha `nIRQ` combinada do `Bcm2835Ic` legado — chamado pelo
    /// hospedeiro a cada fatia, mesmo padrão de {@code Bcm2835Ic#setGpuIrqLine}.
    public void setLegacyIcIrqLine(boolean asserted) {
        legacyIcIrqLine = asserted;
    }

    /// Linha `nIRQ` do core 0 combinada — timer genérico (se habilitado por
    /// `Core0 Timer Interrupt Control`) OU o passthrough do `Bcm2835Ic` legado.
    public boolean irqAsserted() {
        return timerIrqAsserted() || legacyIcIrqLine;
    }

    private boolean timerIrqAsserted() {
        return (timerIrqControl & TIMER_CONTROL_CNTPNSIRQ_BIT) != 0
                && genericTimer.physicalTimerIrqPending();
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
        return switch (offset) {
            case REG_CORE0_TIMER_IRQ_CONTROL -> timerIrqControl;
            case REG_CORE0_IRQ_SOURCE -> (timerIrqAsserted() ? TIMER_CONTROL_CNTPNSIRQ_BIT : 0)
                    | (legacyIcIrqLine ? SOURCE_GPU_IRQ_BIT : 0);
            default -> 0;
        };
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
        if (offset == REG_CORE0_TIMER_IRQ_CONTROL) {
            timerIrqControl = value;
        }
        // Core0 IRQ Source é somente-leitura: escrita ignorada, igual ao hardware real.
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
