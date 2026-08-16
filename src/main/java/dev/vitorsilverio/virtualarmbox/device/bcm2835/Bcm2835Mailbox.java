package dev.vitorsilverio.virtualarmbox.device.bcm2835;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

import java.util.Objects;

/// Mailboxes ARM↔VideoCore do BCM2835 (`ARMCTRL_0_SBM`, `0x2000B800` — `MAIL0_READ` cai em
/// `0x2000B880`, o endereço citado na maioria da documentação) + o **canal de propriedades**
/// (canal 8, `Mailbox-property-interface`), que é como o kernel consulta o firmware no boot
/// (`GET_BOARD_REVISION`/`GET_ARM_MEMORY`/`GET_CLOCK_RATE`/`GET_COMMAND_LINE` etc.).
///
/// Transcrito de `hw/misc/bcm2835_mbox.c` (registradores MAIL0/MAIL1) + `hw/misc/
/// bcm2835_property.c` (formato do buffer de tags) do QEMU, com uma simplificação deliberada
/// (decisão da task F3, "implementar apenas o suficiente para o kernel não travar"): no QEMU
/// real o canal 8 é um DISPOSITIVO FILHO separado, endereçado através de uma segunda
/// `AddressSpace` privada (`mbox_as`) e respondendo de forma assíncrona (GPIO de "disponível"
/// de volta ao mailbox); aqui a mensagem é processada SÍNCRONAMENTE dentro da própria escrita em
/// `MAIL1_WRITE` — mesmo formato de buffer na RAM do guest, resposta imediata em vez de
/// atravessar um barramento privado intermediário.
///
/// Toda tag reconhecida ({@link #GET_ARM_MEMORY}/{@link #GET_BOARD_REVISION}/etc.) responde `0`
/// preenchendo o campo de tamanho com o bit de sucesso ligado — exatamente o que a task pede
/// ("marcar cada tag desconhecida como respondida com valor 0 e ligar o bit de sucesso no
/// cabeçalho"); nenhuma tag teve seu valor real modelado (não há relógio/framebuffer/board-id
/// de verdade por trás).
public final class Bcm2835Mailbox implements AddressSpace {
    public static final int REGION_SIZE = 0x1000;

    private static final int OFFSET_MASK = 0xFF;
    private static final int MAIL0_READ_START = 0x80;
    private static final int MAIL0_READ_END = 0x8C;
    private static final int MAIL0_PEEK = 0x90;
    private static final int MAIL0_SENDER = 0x94;
    private static final int MAIL0_STATUS = 0x98;
    private static final int MAIL0_CONFIG = 0x9C;
    private static final int MAIL1_WRITE_START = 0xA0;
    private static final int MAIL1_WRITE_END = 0xAC;
    private static final int MAIL1_STATUS = 0xB8;

    private static final int ARM_MS_FULL = 0x8000_0000;
    private static final int ARM_MS_EMPTY = 0x4000_0000;
    private static final int ARM_MC_IHAVEDATAIRQEN = 0x0000_0001;

    private static final int MBOX_DEPTH = 32;
    private static final int MBOX_INVALID_DATA = 0x0F;
    private static final int CHANNEL_MASK = 0xF;

    /// Canal do protocolo de propriedades (`MBOX_CHAN_PROPERTY` do QEMU).
    private static final int CHANNEL_PROPERTY = 8;

    /// `RPI_FWREQ_PROPERTY_END` — fecha a lista de tags.
    private static final int TAG_END = 0;
    /// Cabeçalho do buffer de requisição/resposta (`rpi_firmware_prop_request_t`):
    /// `[0]=tamanho total`, `[4]=código de requisição/resposta`, `[8...]=tags`.
    private static final int HEADER_SIZE_BYTES = 8;
    private static final int TAG_HEADER_BYTES = 12; // tag_id(4) + bufsize(4) + resp_code(4)
    /// `(1<<31)` — bit de "é uma resposta" tanto no cabeçalho do buffer quanto em cada tag.
    private static final int RESPONSE_FLAG = 1 << 31;

    private final AddressSpace physicalMemory;

    private final int[] mbox0Reg = new int[MBOX_DEPTH];
    private int mbox0Count;
    private int mbox0Status;
    private int mbox0Config;

    /// Linha de IRQ combinada (`INTERRUPT_ARM_MAILBOX`) — sondada pelo hospedeiro a cada
    /// fatia, mesmo padrão dos demais periféricos.
    public boolean irqAsserted() {
        return (mbox0Status & ARM_MS_EMPTY) == 0 && (mbox0Config & ARM_MC_IHAVEDATAIRQEN) != 0;
    }

    /// @param physicalMemory barramento físico do guest (RAM) onde os buffers de mensagem do
    ///                        protocolo de propriedades vivem — o mailbox lê/escreve ali
    ///                        diretamente, como o QEMU faz via `dma_as`.
    public Bcm2835Mailbox(AddressSpace physicalMemory) {
        this.physicalMemory = Objects.requireNonNull(physicalMemory, "physicalMemory");
        java.util.Arrays.fill(mbox0Reg, MBOX_INVALID_DATA);
        mbox0Status = ARM_MS_EMPTY;
    }

    private void push(int value) {
        if (mbox0Count >= MBOX_DEPTH) {
            return; // mailbox cheia: hardware real também descartaria (ARM_MC_ERROVERFLW).
        }
        mbox0Reg[mbox0Count++] = value;
        updateStatus();
    }

    private int pull() {
        int value = mbox0Reg[0];
        for (int i = 1; i < mbox0Count; i++) {
            mbox0Reg[i - 1] = mbox0Reg[i];
        }
        mbox0Count--;
        mbox0Reg[mbox0Count] = MBOX_INVALID_DATA;
        updateStatus();
        return value;
    }

    private void updateStatus() {
        mbox0Status &= ~(ARM_MS_EMPTY | ARM_MS_FULL);
        if (mbox0Count == 0) {
            mbox0Status |= ARM_MS_EMPTY;
        } else if (mbox0Count == MBOX_DEPTH) {
            mbox0Status |= ARM_MS_FULL;
        }
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
        int offset = address & OFFSET_MASK;
        if (offset >= MAIL0_READ_START && offset < MAIL0_READ_END) {
            return (mbox0Status & ARM_MS_EMPTY) != 0 ? MBOX_INVALID_DATA : pull();
        }
        return switch (offset) {
            case MAIL0_PEEK -> mbox0Count == 0 ? MBOX_INVALID_DATA : mbox0Reg[0];
            case MAIL0_SENDER -> 0;
            case MAIL0_STATUS -> mbox0Status;
            case MAIL0_CONFIG -> mbox0Config;
            case MAIL1_STATUS -> ARM_MS_EMPTY; // canal ARM->VC sempre "vazio": processado na hora.
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
        int offset = address & OFFSET_MASK;
        if (offset >= MAIL1_WRITE_START && offset < MAIL1_WRITE_END) {
            handleArmToVcWrite(value);
            return;
        }
        switch (offset) {
            case MAIL0_CONFIG -> mbox0Config = (mbox0Config & ~ARM_MC_IHAVEDATAIRQEN)
                    | (value & ARM_MC_IHAVEDATAIRQEN);
            case MAIL0_SENDER -> { /* somente leitura na prática: sem efeito. */ }
            default -> { /* offset desconhecido: sem efeito, igual ao QEMU. */ }
        }
    }

    private void handleArmToVcWrite(int value) {
        int channel = value & CHANNEL_MASK;
        int bufferAddress = value & ~CHANNEL_MASK;
        if (channel == CHANNEL_PROPERTY) {
            processPropertyMessage(bufferAddress);
        }
        // Qualquer canal (property ou não): resposta imediata (mesmo valor de volta) — não há
        // fila real de espera nesta simplificação, ver Javadoc da classe.
        push(bufferAddress | channel);
    }

    /// Processa o buffer de tags em `bufferAddress` (RAM do guest) — formato descrito no
    /// Javadoc da classe. Toda tag recebe resposta zerada com o bit de sucesso ligado.
    private void processPropertyMessage(int bufferAddress) {
        int totalLength = physicalMemory.read32(bufferAddress);
        int cursor = bufferAddress + HEADER_SIZE_BYTES;
        int end = bufferAddress + totalLength;
        while (cursor + TAG_HEADER_BYTES <= end) {
            int tagId = physicalMemory.read32(cursor);
            int bufferSize = physicalMemory.read32(cursor + 4);
            if (tagId == TAG_END) {
                break;
            }
            zeroFill(cursor + TAG_HEADER_BYTES, bufferSize);
            physicalMemory.write32(cursor + 8, RESPONSE_FLAG | bufferSize);
            cursor += TAG_HEADER_BYTES + bufferSize;
        }
        physicalMemory.write32(bufferAddress + 4, RESPONSE_FLAG);
    }

    private void zeroFill(int address, int lengthBytes) {
        for (int i = 0; i < lengthBytes; i++) {
            physicalMemory.write8(address + i, 0);
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
