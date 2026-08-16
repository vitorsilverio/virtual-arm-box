package dev.vitorsilverio.virtualarmbox.device.bcm2835;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

/// BCM2835 System Timer (`0x20003000` — *BCM2835 ARM Peripherals*, cap. 12), o clocksource
/// livre de 64 bits (1MHz) + 4 comparadores que `drivers/clocksource/bcm2835_timer.c` usa como
/// clockevent. Transcrito de `hw/timer/bcm2835_systmr.c` do QEMU — o comentário de cabeçalho
/// daquele arquivo ("only the free running counter is implemented") está desatualizado frente
/// ao próprio código: os 4 comparadores E a IRQ por comparador SÃO implementados lá, e são
/// transcritos aqui do mesmo jeito.
///
/// Modelo de tempo: sem relógio de parede real — o hospedeiro converte ciclos de CPU emulados
/// em "microssegundos" do contador via {@link #advance}, mesmo padrão de
/// `dev.vitorsilverio.virtualarmbox.device.Sp804DualTimer#advance` (que já resolve o mesmo
/// problema para o par SP804 do `versatilepb`).
public final class Bcm2835SystemTimer implements AddressSpace {
    /// Janela MMIO mapeada pelo hospedeiro (o registrador mais alto usado, `COMPARE3` em
    /// `0x18`, cabe em bem menos que uma página de 4KiB — a página inteira é reservada pelo
    /// mesmo motivo que os periféricos do `versatilepb`: granularidade fixa do barramento
    /// físico, ver {@code VersatilePbMachine#PHYSICAL_PAGE_SHIFT}).
    public static final int REGION_SIZE = 0x1000;

    private static final int REG_CTRL_STATUS = 0x00;
    private static final int REG_COUNTER_LOW = 0x04;
    private static final int REG_COUNTER_HIGH = 0x08;
    private static final int REG_COMPARE0 = 0x0C;
    private static final int REG_COMPARE1 = 0x10;
    private static final int REG_COMPARE2 = 0x14;
    private static final int REG_COMPARE3 = 0x18;

    private static final int COMPARE_COUNT = 4;

    /// Proporção ciclos-de-CPU-emulados por microssegundo do contador livre — nomeada por G6,
    /// mesmo raciocínio de `Sp804DualTimer#HOST_CYCLES_PER_TIMER_TICK`: não modela o clock real
    /// do ARM1176, só garante que os comparadores disparem numa cadência que o kernel aceita
    /// como "clockevent periódico" sem perder ticks.
    private static final long HOST_CYCLES_PER_MICROSECOND = 4;

    private long counterMicros;
    private long microsAccumulator;
    private int ctrlStatus;
    private final int[] compare = new int[COMPARE_COUNT];
    private final boolean[] compareArmed = new boolean[COMPARE_COUNT];

    /// Avança o contador livre por `deltaCycles` ciclos de CPU emulados e reavalia os 4
    /// comparadores (cada um dispara sua IRQ, em `bcm2835_ic_set_gpu_irq(INTERRUPT_TIMERn)`,
    /// quando o contador alcança o valor armado — `bcm2835_systmr_timer_expire` do QEMU).
    public void advance(long deltaCycles) {
        microsAccumulator += deltaCycles;
        long micros = microsAccumulator / HOST_CYCLES_PER_MICROSECOND;
        if (micros == 0) {
            return;
        }
        microsAccumulator -= micros * HOST_CYCLES_PER_MICROSECOND;
        counterMicros += micros;
        int counterLow = (int) counterMicros;
        for (int index = 0; index < COMPARE_COUNT; index++) {
            if (compareArmed[index] && counterLow - compare[index] >= 0) {
                compareArmed[index] = false;
                ctrlStatus |= 1 << index;
            }
        }
    }

    /// Linha de IRQ do comparador `index` (`INTERRUPT_TIMER0..3` no `Bcm2835Ic`).
    public boolean irqAsserted(int index) {
        return (ctrlStatus & (1 << index)) != 0;
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
            case REG_CTRL_STATUS -> ctrlStatus;
            case REG_COUNTER_LOW -> (int) counterMicros;
            case REG_COUNTER_HIGH -> (int) (counterMicros >>> 32);
            case REG_COMPARE0 -> compare[0];
            case REG_COMPARE1 -> compare[1];
            case REG_COMPARE2 -> compare[2];
            case REG_COMPARE3 -> compare[3];
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
        switch (offset) {
            case REG_CTRL_STATUS -> ctrlStatus &= ~value; // ack: escrever 1 limpa o bit.
            case REG_COMPARE0 -> armCompare(0, value);
            case REG_COMPARE1 -> armCompare(1, value);
            case REG_COMPARE2 -> armCompare(2, value);
            case REG_COMPARE3 -> armCompare(3, value);
            default -> { /* COUNTER_LOW/HIGH somente-leitura: escrita ignorada, igual ao QEMU. */ }
        }
    }

    private void armCompare(int index, int value) {
        compare[index] = value;
        compareArmed[index] = true;
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
