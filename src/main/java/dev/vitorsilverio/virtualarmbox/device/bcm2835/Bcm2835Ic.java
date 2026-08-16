package dev.vitorsilverio.virtualarmbox.device.bcm2835;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

/// Controlador de interrupção do BCM2835 (`ARMCTRL-IC`, `0x2000B200` — *BCM2835 ARM
/// Peripherals*, cap. 7): 64 fontes GPU + 8 fontes ARM privadas, agregadas numa única linha
/// `nIRQ`/`nFIQ` para o core. Transcrito de `hw/intc/bcm2835_ic.c` do QEMU — os 11 "IRQ
/// duplicados" nos bits 10-20 do registrador `IRQ_PENDING_BASIC` (`irq_dups`) não são um atalho
/// nosso, é assim que o hardware real expõe as fontes GPU mais comuns sem exigir uma leitura de
/// `IRQ_PENDING_1`/`_2` separada.
///
/// Modelo de tempo/IRQ: sem push síncrono — o hospedeiro consulta {@link #irqAsserted()} por
/// sondagem a cada fatia, mesmo padrão de
/// {@link dev.vitorsilverio.virtualarmbox.device.Pl190Vic} no `versatilepb`.
public final class Bcm2835Ic implements AddressSpace {
    public static final int REGION_SIZE = 0x1000;

    /// 64 fontes GPU + 8 fontes ARM privadas (`raspi_platform.h`: `INTERRUPT_*`).
    public static final int GPU_IRQ_COUNT = 64;
    public static final int ARM_IRQ_COUNT = 8;

    private static final int REG_IRQ_PENDING_BASIC = 0x00;
    private static final int REG_IRQ_PENDING_1 = 0x04;
    private static final int REG_IRQ_PENDING_2 = 0x08;
    private static final int REG_FIQ_CONTROL = 0x0C;
    private static final int REG_IRQ_ENABLE_1 = 0x10;
    private static final int REG_IRQ_ENABLE_2 = 0x14;
    private static final int REG_IRQ_ENABLE_BASIC = 0x18;
    private static final int REG_IRQ_DISABLE_1 = 0x1C;
    private static final int REG_IRQ_DISABLE_2 = 0x20;
    private static final int REG_IRQ_DISABLE_BASIC = 0x24;

    /// Fontes GPU replicadas nos bits 10-20 de `IRQ_PENDING_BASIC` (`irq_dups` do QEMU).
    private static final int[] IRQ_DUPS = {7, 9, 10, 18, 19, 53, 54, 55, 56, 57, 62};

    private long gpuIrqLevel;
    private long gpuIrqEnable;
    private int armIrqLevel;
    private int armIrqEnable;
    private boolean fiqEnable;
    private int fiqSelect;

    /// Define o nível de uma das 64 linhas GPU (chamado pelo hospedeiro a partir de
    /// `Bcm2835SystemTimer#irqAsserted`/`Bcm2835Mailbox#irqAsserted`/etc.).
    public void setGpuIrqLine(int source, boolean asserted) {
        long mask = 1L << source;
        gpuIrqLevel = asserted ? (gpuIrqLevel | mask) : (gpuIrqLevel & ~mask);
    }

    /// Define o nível de uma das 8 linhas ARM privadas (ex.: `INTERRUPT_ARM_MAILBOX`).
    public void setArmIrqLine(int source, boolean asserted) {
        int mask = 1 << source;
        armIrqLevel = asserted ? (armIrqLevel | mask) : (armIrqLevel & ~mask);
    }

    /// Linha `nIRQ` combinada (`bcm2835_ic_update`: fontes habilitadas, GPU ou ARM, que não
    /// estejam desviadas para FIQ).
    public boolean irqAsserted() {
        return (gpuIrqLevel & gpuIrqEnable) != 0 || (armIrqLevel & armIrqEnable) != 0;
    }

    /// Linha `nFIQ` — só a fonte única selecionada em `FIQ_CONTROL`, quando habilitada.
    public boolean fiqAsserted() {
        if (!fiqEnable) {
            return false;
        }
        if (fiqSelect >= GPU_IRQ_COUNT) {
            int armBit = fiqSelect - GPU_IRQ_COUNT;
            return ((armIrqLevel >>> armBit) & 1) != 0;
        }
        return ((gpuIrqLevel >>> fiqSelect) & 1) != 0;
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
        long gpuPending = gpuIrqLevel & gpuIrqEnable;
        return switch (offset) {
            case REG_IRQ_PENDING_BASIC -> pendingBasic(gpuPending);
            case REG_IRQ_PENDING_1 -> (int) gpuPending;
            case REG_IRQ_PENDING_2 -> (int) (gpuPending >>> 32);
            case REG_FIQ_CONTROL -> (fiqEnable ? 1 << 7 : 0) | fiqSelect;
            case REG_IRQ_ENABLE_1 -> (int) gpuIrqEnable;
            case REG_IRQ_ENABLE_2 -> (int) (gpuIrqEnable >>> 32);
            case REG_IRQ_ENABLE_BASIC -> armIrqEnable;
            case REG_IRQ_DISABLE_1 -> (int) ~gpuIrqEnable;
            case REG_IRQ_DISABLE_2 -> (int) (~gpuIrqEnable >>> 32);
            case REG_IRQ_DISABLE_BASIC -> ~armIrqEnable;
            default -> 0;
        };
    }

    private int pendingBasic(long gpuPending) {
        int res = armIrqLevel & armIrqEnable;
        res |= (gpuPending != 0 ? 1 : 0) << 8;
        res |= ((gpuPending >>> 32) != 0 ? 1 : 0) << 9;
        for (int i = 0; i < IRQ_DUPS.length; i++) {
            res |= (int) ((gpuPending >>> IRQ_DUPS[i]) & 1) << (i + 10);
        }
        return res;
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
        switch (offset) {
            case REG_FIQ_CONTROL -> {
                fiqSelect = value & 0x7F;
                fiqEnable = (value & (1 << 7)) != 0;
            }
            case REG_IRQ_ENABLE_1 -> gpuIrqEnable |= value & 0xFFFF_FFFFL;
            case REG_IRQ_ENABLE_2 -> gpuIrqEnable |= (value & 0xFFFF_FFFFL) << 32;
            case REG_IRQ_ENABLE_BASIC -> armIrqEnable |= value & 0xFF;
            case REG_IRQ_DISABLE_1 -> gpuIrqEnable &= ~(value & 0xFFFF_FFFFL);
            case REG_IRQ_DISABLE_2 -> gpuIrqEnable &= ~((value & 0xFFFF_FFFFL) << 32);
            case REG_IRQ_DISABLE_BASIC -> armIrqEnable &= ~value & 0xFF;
            default -> { /* offset desconhecido: sem efeito, igual ao QEMU. */ }
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
