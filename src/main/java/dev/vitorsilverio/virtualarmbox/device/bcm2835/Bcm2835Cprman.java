package dev.vitorsilverio.virtualarmbox.device.bcm2835;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

/// CPRMAN — Clock Power Reset MANager do BCM2835 (`CPRMAN_OFFSET = 0x101000` em
/// `raspi_platform.h` do QEMU, físico `0x2010_1000`). Transcrito de `hw/misc/bcm2835_cprman.c` +
/// `include/hw/misc/bcm2835_cprman_internals.h` do QEMU, mas **deliberadamente mínimo** (achado
/// da task F3, sessão de fechamento do M3, ver Javadoc de {@link dev.vitorsilverio.virtualarmbox.Bcm2835Machine}):
/// nenhuma matemática real de PLL/divisor é modelada — só o suficiente para o driver de kernel
/// `drivers/clk/bcm/clk-bcm2835.c` não travar em `deferred_probe_work_func`.
///
/// **Achado real, via trace de boot desta sessão**: sem NENHUM periférico neste endereço (caindo
/// em `OpenBus`), o log do kernel real mostra `bcm2835-clk 20101000.cprman: plld: couldn't lock
/// PLL` seguido de `error -ETIMEDOUT: failed to register clk 'plld'` — o driver marca `plld` como
/// "critical" (sempre habilitado, independente de consumidor) e tenta prepará-lo no `probe()`
/// síncrono; como `OpenBus` sempre devolve `0` para {@link #CM_LOCK} (`0x114`), o bit de "PLL
/// travado" (`FLOCKD`, bit 11) nunca liga e o driver espera até estourar o timeout, cai no
/// mecanismo de *deferred probe* do kernel e só tenta de novo bem mais tarde, numa
/// `workqueue` assíncrona. O driver PL011 (`amba-pl011`) **efetivamente consegue** se registrar
/// depois disso (`"20201000.serial: ttyAMA0 ... is a PL011 rev2"` aparece no log) — mas tarde
/// demais: o PID 1 (`/init`) já abriu `/dev/console` antes disso e ficou preso no console
/// `earlycon` antigo, que não processa entrada digitada — daí o shell nunca responder a nada
/// digitado no teste de M3, mesmo com um prompt eventualmente aparecendo.
///
/// **Fix mínimo**: {@link #CM_LOCK} sempre devolve todos os bits `FLOCKx` ligados (PLL
/// "travado" desde sempre, computado — nunca lido do array de armazenamento), fazendo o
/// `probe()` síncrono do driver `clk-bcm2835` ter sucesso na primeira tentativa, sem esperar o
/// timeout nem cair em *deferred probe*. Todo o resto do espaço de registrador é armazenamento
/// simples (RAZ/WI com round-trip, mesmo precedente de {@code Bcm2835Cp15Extras}/`c7`) — nenhuma
/// tentativa de calcular taxa de clock real; os dois registradores do clock de UART (
/// {@link #CM_UARTCTL}/{@link #CM_UARTDIV}) são pré-semeados com os valores de reset do QEMU só
/// para o cálculo de baud-rate do `pl011_set_termios` não dividir por zero (achado colateral já
/// registrado em sessão anterior — "Division by zero in kernel").
public final class Bcm2835Cprman implements AddressSpace {
    /// Janela MMIO mapeada — cobre até o registrador de reset mais alto usado por este driver
    /// (`A2W_PLLD_DSI1` em `0x1640`) com folga; o restante do espaço real do CPRMAN (existem
    /// dezenas de outros PLLs/canais fora do escopo desta task) fica como armazenamento simples
    /// não-inicializado, nunca lido por este kernel/DTB.
    public static final int REGION_SIZE = 0x2000;

    /// `CM_LOCK` (`0x114`): bits de status "PLL travado", um por PLL (`FLOCKA..FLOCKH`). Esta
    /// classe não modela PLLs de verdade — o valor devolvido é sempre "todos travados" (ver
    /// Javadoc da classe).
    private static final int CM_LOCK = 0x114;
    /// `FLOCKA`(8)..`FLOCKH`(12) — `bcm2835_cprman_internals.h` do QEMU. Nomeado por G6.
    private static final int CM_LOCK_ALL_PLLS_LOCKED = 0b1_1111 << 8;

    private static final int CM_UARTCTL = 0x0F0;
    private static final int CM_UARTDIV = 0x0F4;
    /// Valores de reset do QEMU (`CPRMAN_CLOCK_UART` em `bcm2835_cprman_internals.h`) — só para
    /// {@code pl011_set_termios} não ler um divisor zero (achado colateral, não o bloqueio
    /// principal de M3).
    private static final int CM_UARTCTL_RESET = 0x0000_0296;
    private static final int CM_UARTDIV_RESET = 0x0000_A6AB;

    private final int[] registers = new int[REGION_SIZE / Integer.BYTES];

    public Bcm2835Cprman() {
        registers[CM_UARTCTL / Integer.BYTES] = CM_UARTCTL_RESET;
        registers[CM_UARTDIV / Integer.BYTES] = CM_UARTDIV_RESET;
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
        if (offset == CM_LOCK) {
            return CM_LOCK_ALL_PLLS_LOCKED;
        }
        return registers[offset / Integer.BYTES];
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
        if (offset == CM_LOCK) {
            return; // somente-leitura: computado, nunca armazenado.
        }
        registers[offset / Integer.BYTES] = value;
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
