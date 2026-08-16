package dev.vitorsilverio.virtualarmbox.boot;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Testa {@link ZImageDecompressor}: primeiro contra um zImage SINTÉTICO (stub falso + payload
/// gzip real, para não depender de asset nenhum), depois contra o `kernel.img` REAL de
/// `testdata/raspi1/` (task F3) para provar que o formato entendido bate com o build oficial do
/// `raspberrypi/firmware`.
class ZImageDecompressorTest {
    private static final Path KERNEL_IMG = Path.of("testdata", "raspi1", "kernel.img");

    @Test
    void decompressesSyntheticStubPlusGzipPayload() throws IOException {
        byte[] payload = new byte[4096];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 7);
        }
        byte[] zImage = buildSyntheticZImage(payload);

        byte[] result = ZImageDecompressor.decompress(zImage);

        assertArrayEquals(payload, result);
    }

    @Test
    void throwsWhenNoGzipMagicPresent() {
        byte[] noGzip = new byte[64]; // tudo zero, nenhum magic 1F 8B 08 em lugar nenhum.

        assertThrows(IllegalArgumentException.class, () -> ZImageDecompressor.decompress(noGzip));
    }

    @Test
    void decompressesRealKernelImg() throws IOException {
        assumeTrue(Files.exists(KERNEL_IMG), "asset real ausente nesta checkout");
        byte[] zImage = Files.readAllBytes(KERNEL_IMG);

        byte[] decompressed = ZImageDecompressor.decompress(zImage);

        // A imagem descomprimida (`vmlinux`/`Image`) do kernel Linux ARM começa com o `stext`
        // real (`arch/arm/kernel/head.S`): `mrs r9, cpsr` é a primeira instrução de qualquer
        // build — 0xE10F9000 em little-endian ARM puro. Prova que o payload achado é mesmo o
        // kernel descomprimido, não lixo.
        assertTrue(decompressed.length > zImage.length,
                "kernel descomprimido deveria ser maior que o zImage comprimido");
        int firstWord = (decompressed[0] & 0xFF)
                | ((decompressed[1] & 0xFF) << 8)
                | ((decompressed[2] & 0xFF) << 16)
                | ((decompressed[3] & 0xFF) << 24);
        assertTrue(firstWord == 0xE10F9000,
                "primeira instrução esperada era 'mrs r9, cpsr' (0xE10F9000), obtive 0x"
                        + Integer.toHexString(firstWord));
    }

    /// Monta um zImage mínimo o bastante para {@link ZImageDecompressor#findGzipStart}: 11
    /// palavras de cabeçalho de stub (zero, não importa o conteúdo, só o tamanho) seguidas de um
    /// payload gzip real de `payload`.
    private static byte[] buildSyntheticZImage(byte[] payload) throws IOException {
        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(gzipped)) {
            gzip.write(payload);
        }
        byte[] stub = new byte[44]; // 11 palavras de 32 bits, conteúdo irrelevante para o teste.
        Arrays.fill(stub, (byte) 0);
        byte[] zImage = new byte[stub.length + gzipped.size()];
        System.arraycopy(stub, 0, zImage, 0, stub.length);
        System.arraycopy(gzipped.toByteArray(), 0, zImage, stub.length, gzipped.size());
        return zImage;
    }
}
