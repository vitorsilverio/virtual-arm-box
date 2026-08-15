package dev.vitorsilverio.virtualarmbox.device;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

/// SP804 dual-timer (ARM PrimeCell), usado como fonte de `clockevent` do kernel
/// (`sp804_clockevents_init(TIMER0_VA_BASE, ...)` em `arch/arm/mach-versatile/core.c`,
/// timer0 do primeiro par em `0x101e2000`). Transcrito de `hw/timer/arm_timer.c`
/// (`arm_timer_state`/`SP804State`) do QEMU — dois temporizadores independentes de 32/16
/// bits (`TimerControl.32BIT`), decrescentes, com recarga automática em modo periódico,
/// combinados numa única linha de IRQ (`level[0] || level[1]`, `sp804_set_irq`).
///
/// Modelo de tempo: em vez de um relógio de parede real, o hospedeiro converte ciclos de
/// CPU emulados (`ArmCore#cycles()`) em "ticks" do temporizador via {@link #advance}, na
/// mesma proporção do clock fixo de 1MHz que `arch/arm/mach-versatile/core.c` programa
/// para o `sp804_clk` (`.rate = 1000000` — não lido de hardware, constante do driver) —
/// {@link #HOST_CYCLES_PER_TIMER_TICK} é essa proporção (aproximada: não modela o clock
/// real do ARM1176, só garante que os ticks cheguem na cadência que o kernel espera para
/// não perder o `clockevent` periódico).
public final class Sp804DualTimer implements AddressSpace {
    /// Tamanho da janela MMIO (`memory_region_init_io(..., 0x1000)` no QEMU).
    public static final int REGION_SIZE = 0x1000;

    private static final int TIMER1_OFFSET = 0x20;
    private static final int TIMER_WINDOW_SIZE = 0x20;
    private static final int ID_REGION_START = 0xFE0;
    private static final int ID_REGION_END = 0x1000;

    /// `sp804_ids` do QEMU: TimerID (4 bytes) + PrimeCell ID (4 bytes).
    private static final int[] SP804_IDS = {0x04, 0x18, 0x14, 0x00, 0x0d, 0xf0, 0x05, 0xb1};

    /// Proporção ciclos-de-CPU-emulados por tick de 1MHz do temporizador (ver Javadoc da
    /// classe) — nomeada por G6, não um número mágico solto no meio do código de avanço.
    private static final long HOST_CYCLES_PER_TIMER_TICK = 4;

    private final ArmSubTimer timer0 = new ArmSubTimer();
    private final ArmSubTimer timer1 = new ArmSubTimer();
    private long tickAccumulator;

    /// Avança o par de temporizadores por `deltaCycles` ciclos de CPU emulados (ver
    /// {@link #HOST_CYCLES_PER_TIMER_TICK}). A linha de IRQ combinada é consultada por
    /// sondagem via {@link #irqAsserted()} (mesmo padrão de {@link Pl011Uart} — o
    /// hospedeiro repassa ao {@link Pl190Vic} a cada fatia do laço principal).
    public void advance(long deltaCycles) {
        tickAccumulator += deltaCycles;
        long ticks = tickAccumulator / HOST_CYCLES_PER_TIMER_TICK;
        if (ticks == 0) {
            return;
        }
        tickAccumulator -= ticks * HOST_CYCLES_PER_TIMER_TICK;
        timer0.advance(ticks);
        timer1.advance(ticks);
    }

    /// Linha de IRQ combinada (`sp804_set_irq`: `level[0] || level[1]`).
    public boolean irqAsserted() {
        return timer0.irqAsserted() || timer1.irqAsserted();
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
            return idIndex < SP804_IDS.length ? SP804_IDS[idIndex] : 0;
        }
        if (offset < TIMER1_OFFSET) {
            return timer0.read(offset);
        }
        if (offset < TIMER1_OFFSET + TIMER_WINDOW_SIZE) {
            return timer1.read(offset - TIMER1_OFFSET);
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
        if (offset < TIMER1_OFFSET) {
            timer0.write(offset, value);
        } else if (offset < TIMER1_OFFSET + TIMER_WINDOW_SIZE) {
            timer1.write(offset - TIMER1_OFFSET, value);
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

    /// Um dos dois temporizadores independentes do par SP804 (`arm_timer_state` do QEMU).
    private static final class ArmSubTimer {
        private static final int REG_LOAD = 0x00;
        private static final int REG_VALUE = 0x04;
        private static final int REG_CONTROL = 0x08;
        private static final int REG_INTCLR = 0x0C;
        private static final int REG_RIS = 0x10;
        private static final int REG_MIS = 0x14;
        private static final int REG_BGLOAD = 0x18;

        private static final int CTRL_ONESHOT = 1;
        private static final int CTRL_32BIT = 1 << 1;
        private static final int CTRL_IE = 1 << 5;
        private static final int CTRL_PERIODIC = 1 << 6;
        private static final int CTRL_ENABLE = 1 << 7;

        private int control = CTRL_IE; // default do QEMU (arm_timer_init).
        private int limit;
        private long value;
        private boolean intLevel;

        int read(int offset) {
            return switch (offset) {
                case REG_LOAD, REG_BGLOAD -> limit;
                case REG_VALUE -> (int) value;
                case REG_CONTROL -> control;
                case REG_RIS -> intLevel ? 1 : 0;
                case REG_MIS -> (control & CTRL_IE) == 0 ? 0 : (intLevel ? 1 : 0);
                default -> 0;
            };
        }

        /// @return {@code true} se a linha de IRQ pode ter mudado (recalibração ou `INTCLR`)
        boolean write(int offset, int rawValue) {
            switch (offset) {
                case REG_LOAD -> {
                    limit = rawValue;
                    recalibrate(true);
                }
                case REG_VALUE -> { /* somente leitura em hardware real: ignorado. */ }
                case REG_CONTROL -> {
                    control = rawValue;
                    recalibrate((control & CTRL_ENABLE) != 0);
                }
                case REG_INTCLR -> intLevel = false;
                case REG_BGLOAD -> {
                    limit = rawValue;
                    recalibrate(false);
                }
                default -> { /* offset desconhecido: sem efeito. */ }
            }
            return true;
        }

        private void recalibrate(boolean reload) {
            long freeRunningLimit = (control & CTRL_32BIT) != 0 ? 0xFFFF_FFFFL : 0xFFFFL;
            long effectiveLimit = (control & (CTRL_PERIODIC | CTRL_ONESHOT)) == 0
                    ? freeRunningLimit
                    : (limit & 0xFFFF_FFFFL);
            if (reload) {
                value = effectiveLimit;
            }
        }

        /// @return {@code true} se disparou uma IRQ nova neste avanço
        boolean advance(long ticks) {
            if ((control & CTRL_ENABLE) == 0 || ticks <= 0) {
                return false;
            }
            boolean fired = false;
            long remaining = ticks;
            while (remaining > 0) {
                if (value > remaining) {
                    value -= remaining;
                    remaining = 0;
                } else {
                    remaining -= value;
                    value = 0;
                    intLevel = true;
                    fired = true;
                    boolean periodicOrRepeat = (control & (CTRL_PERIODIC | CTRL_ONESHOT)) == CTRL_PERIODIC
                            || (control & (CTRL_PERIODIC | CTRL_ONESHOT)) == 0;
                    if ((control & CTRL_ONESHOT) != 0) {
                        control &= ~CTRL_ENABLE; // um-tiro: para após disparar (semântica real).
                        remaining = 0;
                    } else if (periodicOrRepeat) {
                        recalibrate(true);
                        if (value == 0) {
                            break; // limite 0: evita loop infinito, próximo advance() dispara de novo.
                        }
                    }
                }
            }
            return fired;
        }

        boolean irqAsserted() {
            return intLevel && (control & CTRL_IE) != 0;
        }
    }
}
