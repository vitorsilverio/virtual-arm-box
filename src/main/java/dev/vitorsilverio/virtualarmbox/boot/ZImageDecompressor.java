package dev.vitorsilverio.virtualarmbox.boot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.zip.GZIPInputStream;

/// Descompressor de `zImage` ARM feito no HOST — achado real da sessão 1/3 da task F3 (ver
/// `Raspi1BootTest`): descomprimir os ~7,7MB do `kernel.img` REAL byte a byte dentro do guest
/// (o `inflate()` do próprio stub `head.S`/`misc.c` do kernel) custa ~750 milhões de ciclos e
/// não termina num orçamento de sessão/CI razoável. Esta classe faz a MESMA descompressão fora
/// do guest, com `java.util.zip` (implementação nativa da JVM), e devolve os bytes já prontos
/// para carregar direto no endereço de link esperado — pulando o estágio caro de `inflate()`
/// guest-side inteiramente.
///
/// ## Formato do `zImage` (`arch/arm/boot/compressed/head.S` do kernel Linux)
///
/// Os primeiros bytes são um stub de descompressão auto-relocável (ARM real, não dado):
/// 8 NOPs de alinhamento, uma instrução `b` de desvio, e um cabeçalho de 3 palavras de 32 bits
/// (`0x016F2818` "magic numbers to help the loader", endereço absoluto de carga — `0` quando o
/// stub é PIC/multiplataforma, como é o caso deste `kernel.img` do `raspberrypi/firmware` — e o
/// fim da região de dados do zImage). Depois do stub vem, embutido cru, um payload **gzip
/// padrão** (o `piggy.gzip` gerado pelo `Makefile` do kernel) — localizável pelo magic
/// `1F 8B 08` (`ID1 ID2 CM=deflate`), a mesma técnica do `scripts/extract-vmlinux` do próprio
/// kernel Linux.
///
/// ## Endereço de destino da imagem descomprimida (`AUTO_ZRELADDR`)
///
/// Este `kernel.img` é um zImage multiplataforma (`start` = 0 no cabeçalho acima), então usa
/// `CONFIG_AUTO_ZRELADDR`: o stub calcula o endereço físico de destino em tempo de execução como
/// `(PC & ~0x07FFFFFF) + TEXT_OFFSET` — isto é, os bits altos de onde o PRÓPRIO zImage foi
/// carregado (alinhados a 128MiB) somados a {@link #TEXT_OFFSET}. Confirmado batendo esta conta
/// contra o `qemu-system-arm -M raspi1ap` (oráculo desta task): para um zImage carregado bem
/// abaixo de 128MiB (como o `KERNEL_LOAD_ADDR` de 64KiB deste repo), o resultado é sempre
/// `TEXT_OFFSET` puro — `0x00008000`, o endereço clássico de boot do Raspberry Pi real. A imagem
/// descomprimida devolvida por {@link #decompress(byte[])} deve ser carregada exatamente nesse
/// endereço, com o PC do core apontando para o primeiro byte dela (é o `stext` do kernel — o
/// mesmo ponto de entrada para o qual o stub do zImage saltaria depois de descomprimir; o
/// protocolo de entrada `r0`/`r1`/`r2`/modo `SVC`/IRQ+FIQ mascaradas/MMU desligada não muda,
/// `Documentation/arm/booting.rst`).
public final class ZImageDecompressor {
    /// `TEXT_OFFSET` padrão do kernel ARM multiplataforma (`arch/arm/Makefile`) — deslocamento
    /// físico onde o `stext` do kernel espera rodar dentro da RAM. É o mesmo endereço de boot
    /// clássico (`0x8000`) que o `start.elf` proprietário usa no Raspberry Pi real.
    public static final int TEXT_OFFSET = 0x0000_8000;

    /// Cabeçalho do stub de descompressão (`head.S`): 8 NOPs de alinhamento + 1 `b` de desvio +
    /// 3 palavras de cabeçalho = 11 palavras de 32 bits. O payload gzip nunca aparece antes
    /// disso — evita casar o magic por acaso dentro do próprio código do stub.
    private static final int STUB_HEADER_WORDS = 11;
    private static final int WORD_BYTES = 4;
    private static final int GZIP_SEARCH_START = STUB_HEADER_WORDS * WORD_BYTES;

    private static final int GZIP_MAGIC_ID1 = 0x1F;
    private static final int GZIP_MAGIC_ID2 = 0x8B;
    private static final int GZIP_MAGIC_CM_DEFLATE = 0x08;

    private ZImageDecompressor() {
    }

    /// Localiza o payload gzip embutido em `zImage` e o descomprime inteiro.
    ///
    /// @param zImage bytes crus do `kernel.img` (zImage ARM)
    /// @return a imagem descomprimida (`vmlinux`/`Image`), pronta para ser carregada em
    ///         `RAM_BASE + `{@link #TEXT_OFFSET} com o PC apontando para o início dela
    /// @throws IllegalArgumentException se nenhum magic gzip for encontrado em `zImage`
    public static byte[] decompress(byte[] zImage) {
        int gzipStart = findGzipStart(zImage);
        try (GZIPInputStream gzip = new GZIPInputStream(
                new ByteArrayInputStream(zImage, gzipStart, zImage.length - gzipStart))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(zImage.length * 2);
            gzip.transferTo(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("payload gzip do zImage corrompido ou incompleto", e);
        }
    }

    private static int findGzipStart(byte[] zImage) {
        for (int i = GZIP_SEARCH_START; i < zImage.length - 2; i++) {
            if ((zImage[i] & 0xFF) == GZIP_MAGIC_ID1
                    && (zImage[i + 1] & 0xFF) == GZIP_MAGIC_ID2
                    && (zImage[i + 2] & 0xFF) == GZIP_MAGIC_CM_DEFLATE) {
                return i;
            }
        }
        throw new IllegalArgumentException(
                "nenhum payload gzip (magic 1F 8B 08) encontrado em zImage de " + zImage.length + " bytes");
    }
}
