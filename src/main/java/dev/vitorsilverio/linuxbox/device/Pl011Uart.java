package dev.vitorsilverio.linuxbox.device;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.Deque;

/// PL011 UART (ARM PrimeCell), usado como console (`console=ttyAMA0`) do hospedeiro
/// `versatilepb` (B4.1.5). Transcrito de `hw/char/pl011.c` do QEMU (oráculo do épico
/// B4.1, ver `arm-jitter/tasks/trilha-b-arquiteturas/b4.1-mmu-softmmu.md`) — os deslocamentos
/// de registrador, a máquina de estados do FIFO (`UARTFR.TXFF`/`RXFE`/`RXFF`/`TXFE`) e os
/// registradores de identificação PrimeCell/Cell (offsets `0xFE0`-`0xFFC`) espelham
/// exatamente aquele arquivo — a Armadilha do épico avisa que o kernel trava cedo sem isso
/// certo, então nada aqui foi improvisado.
///
/// Só o registrador de dados (`UARTDR`), a bandeira (`UARTFR`), controle (`UARTCR`), a
/// máscara/estado de interrupção (`UARTIMSC`/`UARTRIS`/`UARTMIS`/`UARTICR`) e os
/// divisores de baud (`UARTIBRD`/`UARTFBRD`, sem efeito observável — não há E/S serial
/// real por trás, só um {@link OutputStream} de host) têm efeito; os demais (`UARTLCR_H`,
/// `UARTIFLS`, `UARTDMACR`, `UARTILPR`) são armazenamento simples, mesmo padrão RAZ/WI de
/// registrador não modelado do `Cp15VmsaCoprocessor` (B4.1.2).
public final class Pl011Uart implements AddressSpace {
    /// Tamanho da janela MMIO (`memory_region_init_io(..., 0x1000)` no QEMU).
    public static final int REGION_SIZE = 0x1000;

    private static final int REG_DR = 0x00;
    private static final int REG_RSR_ECR = 0x04;
    private static final int REG_FR = 0x18;
    private static final int REG_ILPR = 0x20;
    private static final int REG_IBRD = 0x24;
    private static final int REG_FBRD = 0x28;
    private static final int REG_LCRH = 0x2C;
    private static final int REG_CR = 0x30;
    private static final int REG_IFLS = 0x34;
    private static final int REG_IMSC = 0x38;
    private static final int REG_RIS = 0x3C;
    private static final int REG_MIS = 0x40;
    private static final int REG_ICR = 0x44;
    private static final int REG_DMACR = 0x48;
    private static final int ID_REGION_START = 0xFE0;
    private static final int ID_REGION_END = 0x1000;

    /// `pl011_id_arm` do QEMU (`TYPE_PL011`, não a variante Luminary).
    private static final int[] PRIMECELL_ID_ARM =
            {0x11, 0x10, 0x14, 0x00, 0x0d, 0xf0, 0x05, 0xb1};

    private static final int FLAG_TXFE = 0x80;
    private static final int FLAG_RXFF = 0x40;
    private static final int FLAG_TXFF = 0x20;
    private static final int FLAG_RXFE = 0x10;

    private static final int LCR_FEN = 1 << 4;

    private static final int INT_TX = 1 << 5;
    private static final int INT_RX = 1 << 4;

    /// Profundidade do FIFO quando `UARTLCR_H.FEN` está ligado (PL011 real: 16 bytes).
    private static final int FIFO_DEPTH = 16;

    private final OutputStream consoleOut;
    private final Deque<Integer> rxFifo = new ArrayDeque<>();

    private int flags = 0;
    private int lcr;
    private int cr = 0x300;
    private int ibrd;
    private int fbrd;
    private int ifl = 0x12;
    private int intEnabled;
    private int intLevel;
    private int dmacr;
    private int ilpr;
    private int rsr;

    /// A linha de IRQ combinada (`UARTINTR`) é consultada por sondagem (poll) via
    /// {@link #irqAsserted()} — o hospedeiro chama isso a cada fatia do laço principal e
    /// repassa ao {@link Pl190Vic}, em vez deste dispositivo empurrar eventos síncronos
    /// (mais simples que fiar callbacks circulares entre UART/temporizadores/VIC/core, ao
    /// custo de uma fatia de latência — aceitável para o objetivo desta task).
    ///
    /// @param consoleOut saída de host para onde `UARTDR` escrito é encaminhado (a "porta
    ///                     serial" ligada ao terminal do usuário)
    public Pl011Uart(OutputStream consoleOut) {
        this.consoleOut = consoleOut;
        resetRxFifo();
        resetTxFifo();
    }

    /// Entrega um byte recebido do host (teclado) ao FIFO de recepção — equivalente a
    /// `pl011_fifo_rx_put` do QEMU sendo chamado pelo backend de char device.
    public void receiveByte(int value) {
        int depth = fifoDepth();
        if (rxFifo.size() >= depth) {
            return; // FIFO cheio: hardware real descartaria também.
        }
        rxFifo.addLast(value & 0xFF);
        flags &= ~FLAG_RXFE;
        if (rxFifo.size() == depth) {
            flags |= FLAG_RXFF;
        }
        intLevel |= INT_RX; // read_trigger fixo em 1 (mesmo valor do QEMU, comentário original).
        updateIrq();
    }

    /// Linha de IRQ combinada (`UARTINTR`) — única usada pelo PL190 do versatilepb real.
    public boolean irqAsserted() {
        return (intLevel & intEnabled) != 0;
    }

    private int fifoDepth() {
        return (lcr & LCR_FEN) != 0 ? FIFO_DEPTH : 1;
    }

    private void resetRxFifo() {
        rxFifo.clear();
        flags &= ~FLAG_RXFF;
        flags |= FLAG_RXFE;
    }

    private void resetTxFifo() {
        flags &= ~FLAG_TXFF;
        flags |= FLAG_TXFE;
    }

    private void updateIrq() {
        // Sem push: ver Javadoc do construtor — o hospedeiro consulta irqAsserted() por
        // sondagem. Método mantido (em vez de apagar as chamadas) para espelhar exatamente
        // os pontos de `pl011_update()` do QEMU, caso um push síncrono seja adotado depois.
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
            return idIndex < PRIMECELL_ID_ARM.length ? PRIMECELL_ID_ARM[idIndex] : 0;
        }
        return switch (offset) {
            case REG_DR -> readData();
            case REG_RSR_ECR -> rsr;
            case REG_FR -> flags;
            case REG_ILPR -> ilpr;
            case REG_IBRD -> ibrd;
            case REG_FBRD -> fbrd;
            case REG_LCRH -> lcr;
            case REG_CR -> cr;
            case REG_IFLS -> ifl;
            case REG_IMSC -> intEnabled;
            case REG_RIS -> intLevel;
            case REG_MIS -> intLevel & intEnabled;
            case REG_DMACR -> dmacr;
            default -> 0;
        };
    }

    private int readData() {
        if (rxFifo.isEmpty()) {
            flags |= FLAG_RXFE;
            return 0;
        }
        int value = rxFifo.removeFirst();
        if (rxFifo.isEmpty()) {
            flags |= FLAG_RXFE;
            intLevel &= ~INT_RX;
        }
        flags &= ~FLAG_RXFF;
        updateIrq();
        return value;
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
            case REG_DR -> writeData(value & 0xFF);
            case REG_RSR_ECR -> rsr = 0;
            case REG_FR -> { /* somente leitura: escrita ignorada, igual ao QEMU. */ }
            case REG_ILPR -> ilpr = value;
            case REG_IBRD -> ibrd = value & 0xFFFF;
            case REG_FBRD -> fbrd = value & 0x3F;
            case REG_LCRH -> {
                if (((lcr ^ value) & LCR_FEN) != 0) {
                    resetRxFifo();
                    resetTxFifo();
                }
                lcr = value;
            }
            case REG_CR -> cr = value;
            case REG_IFLS -> ifl = value;
            case REG_IMSC -> {
                intEnabled = value;
                updateIrq();
            }
            case REG_ICR -> {
                intLevel &= ~value;
                updateIrq();
            }
            case REG_DMACR -> dmacr = value;
            default -> { /* offset desconhecido: sem efeito, igual ao QEMU (log-only lá). */ }
        }
    }

    private void writeData(int data) {
        try {
            consoleOut.write(data);
            consoleOut.flush();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        intLevel |= INT_TX;
        updateIrq();
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
