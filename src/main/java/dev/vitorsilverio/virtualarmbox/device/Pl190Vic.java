package dev.vitorsilverio.virtualarmbox.device;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

/// PL190 Vectored Interrupt Controller (VIC primário do `versatilepb`, `0x10140000`),
/// linha em `arch/arm/mach-versatile/core.c: vic_init(VA_VIC_BASE, IRQ_VIC_START, ~0, 0)` —
/// todas as 32 fontes válidas, sem recursos de resume. Transcrito de `hw/intc/pl190.c` do
/// QEMU: 32 linhas de entrada, prioridade vetorizada (16 níveis + um "default"), seleção
/// IRQ/FIQ por fonte (`VICINTSELECT`), interrupções por software (`VICSOFTINT`).
///
/// Este VIC é o único usado por B4.1.5 (SIC secundário do versatilepb real — `0x10003000`
/// — está fora do "Inclui" da task: UART0/timers não passam por ele, só UART3/Ethernet/
/// teclado/mouse/PCI/RTC/MMCI, nenhum implementado aqui).
public final class Pl190Vic implements AddressSpace {
    /// Tamanho da janela MMIO (`memory_region_init_io(..., 0x1000)` no QEMU).
    public static final int REGION_SIZE = 0x1000;

    private static final int NUM_SOURCES = 32;
    /// 16 vetores de usuário + 1 nível "default" (`PL190_NUM_PRIO` do QEMU).
    private static final int NUM_PRIORITY_LEVELS = 17;
    private static final int NUM_VECTORS = 16;

    private static final int REG_IRQSTATUS = 0x00;
    private static final int REG_FIQSTATUS = 0x04;
    private static final int REG_RAWINTR = 0x08;
    private static final int REG_INTSELECT = 0x0C;
    private static final int REG_INTENABLE = 0x10;
    private static final int REG_INTENCLEAR = 0x14;
    private static final int REG_SOFTINT = 0x18;
    private static final int REG_SOFTINTCLEAR = 0x1C;
    private static final int REG_PROTECTION = 0x20;
    private static final int REG_VECTADDR = 0x30;
    private static final int REG_DEFVECTADDR = 0x34;
    private static final int VECTADDR_TABLE_START = 0x100;
    private static final int VECTADDR_TABLE_END = 0x140;
    private static final int VECTCNTL_TABLE_START = 0x200;
    private static final int VECTCNTL_TABLE_END = 0x240;
    private static final int ID_REGION_START = 0xFE0;
    private static final int ID_REGION_END = 0x1000;

    /// Bit "vetor ativo" de `VICVECTCNTLn` (`0x20` no QEMU, bit 5).
    private static final int VECT_CONTROL_ENABLE_BIT = 0x20;
    private static final int VECT_CONTROL_SOURCE_MASK = 0x1F;

    private static final int[] PL190_ID =
            {0x90, 0x11, 0x04, 0x00, 0x0D, 0xf0, 0x05, 0xb1};

    private int level;
    private int softLevel;
    private int irqEnable;
    private int fiqSelect;
    private final int[] vectControl = new int[NUM_VECTORS];
    private final int[] vectAddr = new int[NUM_PRIORITY_LEVELS];
    private final int[] prioMask = new int[NUM_PRIORITY_LEVELS + 1];
    private int protection;
    private int priority = NUM_PRIORITY_LEVELS;
    private final int[] prevPrio = new int[NUM_PRIORITY_LEVELS];

    /// As linhas `nIRQ`/`nFIQ` são consultadas por sondagem (ver {@link #irqAsserted()}/
    /// {@link #fiqAsserted()}) — mesmo padrão de {@link Pl011Uart}/{@link Sp804DualTimer}: o
    /// hospedeiro chama {@link #setIrqLine} para cada fonte a cada fatia do laço principal e
    /// repassa o resultado agregado a `ArmCore#setInterruptLine`.
    public Pl190Vic() {
        prioMask[NUM_PRIORITY_LEVELS] = 0xFFFF_FFFF;
        updateVectors();
    }

    /// Define o nível de uma das 32 linhas de entrada (chamado pelo hospedeiro a partir do
    /// estado de cada periférico — `Pl011Uart#irqAsserted`/`Sp804DualTimer#irqAsserted`).
    public void setIrqLine(int source, boolean asserted) {
        int mask = 1 << source;
        if (asserted) {
            level |= mask;
        } else {
            level &= ~mask;
        }
    }

    /// Nível combinado atual da saída `nIRQ` (o que o hospedeiro repassa a
    /// `ArmCore#setInterruptLine`).
    public boolean irqAsserted() {
        int activeLevel = (level | softLevel) & irqEnable & ~fiqSelect;
        return (activeLevel & prioMask[priority]) != 0;
    }

    /// Nível combinado atual da saída `nFIQ`.
    public boolean fiqAsserted() {
        return ((level | softLevel) & fiqSelect) != 0;
    }

    private void update() {
        // Sem push: ver Javadoc do construtor — irqAsserted()/fiqAsserted() são consultados
        // por sondagem. Método mantido para espelhar exatamente os pontos de `pl190_update()`
        // do QEMU.
    }

    private void updateVectors() {
        int mask = 0;
        for (int i = 0; i < NUM_VECTORS; i++) {
            prioMask[i] = mask;
            if ((vectControl[i] & VECT_CONTROL_ENABLE_BIT) != 0) {
                mask |= 1 << (vectControl[i] & VECT_CONTROL_SOURCE_MASK);
            }
        }
        prioMask[NUM_VECTORS] = mask;
        update();
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
        if (offset >= ID_REGION_START && offset < ID_REGION_END) {
            int idIndex = (offset - ID_REGION_START) >>> 2;
            return idIndex < PL190_ID.length ? PL190_ID[idIndex] : 0;
        }
        if (offset >= VECTADDR_TABLE_START && offset < VECTADDR_TABLE_END) {
            return vectAddr[(offset - VECTADDR_TABLE_START) >>> 2];
        }
        if (offset >= VECTCNTL_TABLE_START && offset < VECTCNTL_TABLE_END) {
            return vectControl[(offset - VECTCNTL_TABLE_START) >>> 2];
        }
        return switch (offset) {
            case REG_IRQSTATUS -> (level | softLevel) & irqEnable & ~fiqSelect;
            case REG_FIQSTATUS -> (level | softLevel) & fiqSelect;
            case REG_RAWINTR -> level | softLevel;
            case REG_INTSELECT -> fiqSelect;
            case REG_INTENABLE -> irqEnable;
            case REG_SOFTINT -> softLevel;
            case REG_PROTECTION -> protection;
            case REG_VECTADDR -> readVectAddrAndRaisePriority();
            case REG_DEFVECTADDR -> vectAddr[NUM_VECTORS];
            default -> 0;
        };
    }

    private int readVectAddrAndRaisePriority() {
        int combined = level | softLevel;
        int i = 0;
        while (i < priority && (combined & prioMask[i + 1]) == 0) {
            i++;
        }
        if (i == NUM_PRIORITY_LEVELS) {
            return vectAddr[NUM_VECTORS];
        }
        if (i < priority) {
            prevPrio[i] = priority;
            priority = i;
            update();
        }
        return vectAddr[priority];
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
    public void write32(int address, int rawValue) {
        int offset = address & (REGION_SIZE - 1);
        if (offset >= VECTADDR_TABLE_START && offset < VECTADDR_TABLE_END) {
            vectAddr[(offset - VECTADDR_TABLE_START) >>> 2] = rawValue;
            updateVectors();
            return;
        }
        if (offset >= VECTCNTL_TABLE_START && offset < VECTCNTL_TABLE_END) {
            vectControl[(offset - VECTCNTL_TABLE_START) >>> 2] = rawValue;
            updateVectors();
            return;
        }
        switch (offset) {
            case REG_IRQSTATUS -> { /* somente leitura: escrita ignorada, igual ao QEMU. */ }
            case REG_INTSELECT -> fiqSelect = rawValue;
            case REG_INTENABLE -> irqEnable |= rawValue;
            case REG_INTENCLEAR -> irqEnable &= ~rawValue;
            case REG_SOFTINT -> softLevel |= rawValue;
            case REG_SOFTINTCLEAR -> softLevel &= ~rawValue;
            case REG_PROTECTION -> protection = rawValue & 1;
            case REG_VECTADDR -> restorePriority();
            case REG_DEFVECTADDR -> vectAddr[NUM_VECTORS] = rawValue;
            default -> { /* offset desconhecido (inclui ITCR 0xC0): sem efeito. */ }
        }
        update();
    }

    private void restorePriority() {
        if (priority < NUM_PRIORITY_LEVELS) {
            priority = prevPrio[priority];
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
