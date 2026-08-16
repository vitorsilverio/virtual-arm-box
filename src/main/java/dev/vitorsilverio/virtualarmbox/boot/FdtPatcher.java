package dev.vitorsilverio.virtualarmbox.boot;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/// Patcher mínimo de Flattened Device Tree (FDT, `dtc`/`Documentation/devicetree/booting-
/// without-of.rst`): sobrescreve propriedades de um `.dtb` já compilado, sem depender de
/// `dtc`/`libfdt` (indisponíveis nesta máquina — ver `testdata/README.md` sobre o mesmo
/// bloqueio de toolchain).
///
/// O formato é simples o bastante para reescrever à mão (todos os campos big-endian):
/// cabeçalho de 10 palavras de 32 bits → mapa de reserva de memória → **bloco de estrutura**
/// (tokens `FDT_BEGIN_NODE`/`FDT_END_NODE`/`FDT_PROP`/`FDT_NOP`/`FDT_END`) → **bloco de
/// strings** (nomes de propriedade, referenciados por deslocamento a partir do bloco de
/// estrutura).
///
/// Duas propriedades são reescritas, ambas já existentes em qualquer `.dtb` real do
/// `raspberrypi/firmware` (este patcher não sabe CRIAR nós/propriedades novos, só sobrescrever
/// o valor de um já existente):
///
/// - **`/chosen/bootargs`** — para apontar o console (`console=ttyAMA0` + `earlycon`) e o
///   `rdinit`, exatamente como o `AtagsBuilder` faz para o `versatilepb`.
/// - **`/memory@0/reg`** — achado real ao inspecionar `bcm2708-rpi-b.dtb` byte a byte (`od`):
///   o `.dtb` cru vem com `reg = <0x0 0x0>` (endereço 0, TAMANHO ZERO). Não é um bug do arquivo:
///   no Raspberry Pi real, é o `start.elf` (firmware VideoCore, fora do "Inclui" desta task —
///   ver a nota "Sem `start.elf`/`bootcode.bin`") quem detecta a RAM instalada e sobrescreve
///   este campo em tempo de boot antes de entregar o DTB ao kernel. Sem essa reescrita o
///   kernel lê "0 bytes de RAM" e não passa da inicialização de zonas de memória — como o host
///   aqui **é** o bootloader (a mesma decisão de arquitetura do `versatilepb`), é ele quem tem
///   que fazer essa reescrita, não um `start.elf` que não existe neste repo.
///
/// Como o valor de `bootargs` quase sempre tem tamanho diferente do antigo (mas `reg` sempre
/// tem o MESMO tamanho — 2 células de 32 bits, endereço+tamanho, confirmado pela inspeção acima
/// — nenhum recálculo de deslocamento é necessário para ela), o comprimento de uma propriedade
/// PODE mudar: quando muda, o restante do arquivo desliza por `delta` bytes, e os campos de
/// cabeçalho que apontam para depois do ponto de emenda (`size_dt_struct`/`off_dt_strings`/
/// `totalsize`) são corrigidos.
public final class FdtPatcher {
    private static final int MAGIC = 0xD00D_FEED;

    // Índices dos campos do cabeçalho (cada um uma palavra de 32 bits big-endian).
    private static final int FIELD_MAGIC = 0;
    private static final int FIELD_TOTALSIZE = 1;
    private static final int FIELD_OFF_DT_STRUCT = 2;
    private static final int FIELD_OFF_DT_STRINGS = 3;
    private static final int FIELD_OFF_MEM_RSVMAP = 4;
    private static final int FIELD_VERSION = 5;
    private static final int FIELD_LAST_COMP_VERSION = 6;
    private static final int FIELD_BOOT_CPUID_PHYS = 7;
    private static final int FIELD_SIZE_DT_STRINGS = 8;
    private static final int FIELD_SIZE_DT_STRUCT = 9;

    private static final int FDT_BEGIN_NODE = 0x1;
    private static final int FDT_END_NODE = 0x2;
    private static final int FDT_PROP = 0x3;
    private static final int FDT_NOP = 0x4;
    private static final int FDT_END = 0x9;

    /// `reg` do nó `/memory@0`: 2 células de 32 bits (endereço, tamanho) — layout confirmado
    /// por inspeção do `.dtb` real, não um formato genérico assumido às cegas.
    private static final int MEMORY_REG_BYTES = 2 * Integer.BYTES;

    private FdtPatcher() {
    }

    /// Devolve uma cópia de `dtb` com `/chosen/bootargs` substituído por `bootargs` (string
    /// ASCII terminada em NUL, como o kernel espera).
    public static byte[] withBootargs(byte[] dtb, String bootargs) {
        byte[] newValue = (bootargs + "\0").getBytes(StandardCharsets.US_ASCII);
        return withProperty(dtb, "chosen", "bootargs", newValue);
    }

    /// Devolve uma cópia de `dtb` com `/memory@0/reg` substituído por `<0x0 ramSizeBytes>` — ver
    /// Javadoc da classe sobre por que o `.dtb` cru não pode ser usado como está.
    ///
    /// @throws IllegalArgumentException se `ramSizeBytes` não couber num inteiro de 32 bits sem
    ///                                    sinal (o mesmo limite de `#size-cells = 1` do `.dtb`
    ///                                    real inspecionado — RAM de 4GiB ou mais exigiria
    ///                                    `#size-cells = 2`, fora de escopo)
    public static byte[] withMemorySize(byte[] dtb, long ramSizeBytes) {
        if (ramSizeBytes < 0 || ramSizeBytes > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("ramSizeBytes fora do alcance de 32 bits: " + ramSizeBytes);
        }
        byte[] newValue = new byte[MEMORY_REG_BYTES];
        writeWord(newValue, 0, 0); // endereço base — sempre 0x0 nesta máquina.
        writeWord(newValue, Integer.BYTES, (int) ramSizeBytes);
        return withProperty(dtb, "memory@0", "reg", newValue);
    }

    private static byte[] withProperty(byte[] dtb, String nodeName, String propertyName, byte[] newValue) {
        int magic = readWord(dtb, FIELD_MAGIC * Integer.BYTES);
        if (magic != MAGIC) {
            throw new IllegalArgumentException(
                    "não é um FDT válido: magic=0x" + Integer.toHexString(magic));
        }
        int offDtStruct = readWord(dtb, FIELD_OFF_DT_STRUCT * Integer.BYTES);
        int offDtStrings = readWord(dtb, FIELD_OFF_DT_STRINGS * Integer.BYTES);
        int sizeDtStruct = readWord(dtb, FIELD_SIZE_DT_STRUCT * Integer.BYTES);

        int propTokenStart =
                findPropertyToken(dtb, offDtStruct, sizeDtStruct, offDtStrings, nodeName, propertyName);
        int lenFieldAt = propTokenStart + Integer.BYTES;
        int nameoffFieldAt = lenFieldAt + Integer.BYTES;
        int oldLen = readWord(dtb, lenFieldAt);
        int oldPaddedLen = alignUp4(oldLen);
        int dataStart = nameoffFieldAt + Integer.BYTES;
        int suffixStart = dataStart + oldPaddedLen;

        int newLen = newValue.length;
        int newPaddedLen = alignUp4(newLen);
        int delta = newPaddedLen - oldPaddedLen;

        byte[] result = new byte[dtb.length + delta];
        System.arraycopy(dtb, 0, result, 0, lenFieldAt); // até o início do campo `len`, inclusive.
        writeWord(result, lenFieldAt, newLen);
        System.arraycopy(dtb, nameoffFieldAt, result, nameoffFieldAt, Integer.BYTES); // nameoff intacto.
        System.arraycopy(newValue, 0, result, dataStart, newLen);
        Arrays.fill(result, dataStart + newLen, dataStart + newPaddedLen, (byte) 0); // preenchimento.
        System.arraycopy(dtb, suffixStart, result, dataStart + newPaddedLen, dtb.length - suffixStart);

        writeWord(result, FIELD_TOTALSIZE * Integer.BYTES,
                readWord(dtb, FIELD_TOTALSIZE * Integer.BYTES) + delta);
        writeWord(result, FIELD_SIZE_DT_STRUCT * Integer.BYTES, sizeDtStruct + delta);
        writeWord(result, FIELD_OFF_DT_STRINGS * Integer.BYTES, offDtStrings + delta);
        return result;
    }

    /// Varre o bloco de estrutura (rastreando o nó ATUAL numa pilha, já que `reg`/outros nomes
    /// comuns se repetem em vários nós) procurando o token `FDT_PROP` de `propertyName` cujo pai
    /// imediato é `nodeName`. @return o deslocamento do PRÓPRIO token `FDT_PROP` (não do campo
    /// `len`).
    private static int findPropertyToken(byte[] dtb, int offDtStruct, int sizeDtStruct,
                                         int offDtStrings, String nodeName, String propertyName) {
        Deque<String> nodeStack = new ArrayDeque<>();
        int cursor = offDtStruct;
        int end = offDtStruct + sizeDtStruct;
        while (cursor < end) {
            int token = readWord(dtb, cursor);
            switch (token) {
                case FDT_BEGIN_NODE -> {
                    int nameLength = nulTerminatedLength(dtb, cursor + Integer.BYTES);
                    nodeStack.push(readNulTerminatedString(dtb, cursor + Integer.BYTES));
                    cursor += Integer.BYTES + alignUp4(nameLength + 1);
                }
                case FDT_END_NODE -> {
                    nodeStack.pop();
                    cursor += Integer.BYTES;
                }
                case FDT_NOP -> cursor += Integer.BYTES;
                case FDT_PROP -> {
                    int len = readWord(dtb, cursor + Integer.BYTES);
                    int nameoff = readWord(dtb, cursor + 2 * Integer.BYTES);
                    String name = readNulTerminatedString(dtb, offDtStrings + nameoff);
                    if (name.equals(propertyName) && nodeName.equals(nodeStack.peek())) {
                        return cursor;
                    }
                    cursor += 3 * Integer.BYTES + alignUp4(len);
                }
                case FDT_END -> cursor = end;
                default -> throw new IllegalArgumentException(
                        "token de FDT desconhecido 0x" + Integer.toHexString(token)
                                + " no deslocamento " + cursor);
            }
        }
        throw new IllegalArgumentException(
                "propriedade '" + propertyName + "' do nó '" + nodeName + "' não encontrada no FDT"
                        + " (este patcher não cria nós/propriedades novos)");
    }

    private static int nulTerminatedLength(byte[] data, int start) {
        int i = start;
        while (data[i] != 0) {
            i++;
        }
        return i - start;
    }

    private static String readNulTerminatedString(byte[] data, int start) {
        int length = nulTerminatedLength(data, start);
        return new String(data, start, length, StandardCharsets.US_ASCII);
    }

    private static int alignUp4(int value) {
        return (value + 3) & ~3;
    }

    private static int readWord(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static void writeWord(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }
}
