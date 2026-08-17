package dev.vitorsilverio.virtualarmbox.boot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Testa {@link FdtPatcher} contra o `.dtb` REAL de `testdata/raspi1/` (task F3) — não um FDT
/// sintético escrito à mão, para provar que o patcher entende o formato que `dtc` de verdade
/// produz.
class FdtPatcherTest {
    private static final Path DTB = Path.of("testdata", "raspi1", "bcm2708-rpi-b.dtb");

    @Test
    void patchesBootargsToLongerValue() throws IOException {
        assumeTrue(Files.exists(DTB), "asset real ausente nesta checkout");
        byte[] original = Files.readAllBytes(DTB);
        String cmdline = "console=ttyAMA0,115200 earlycon root=/dev/ram rdinit=/init";

        byte[] patched = FdtPatcher.withBootargs(original, cmdline);

        assertHeaderConsistent(patched);
        assertContainsAsciiOnce(patched, cmdline + "\0");
    }

    @Test
    void patchesBootargsToShorterValue() throws IOException {
        assumeTrue(Files.exists(DTB), "asset real ausente nesta checkout");
        byte[] original = Files.readAllBytes(DTB);

        byte[] patched = FdtPatcher.withBootargs(original, "x");

        assertHeaderConsistent(patched);
        // "x\0" sozinho seria frágil demais para procurar (ocorre por acaso em qualquer lugar);
        // a checagem forte aqui é o cabeçalho consistente + round-trip abaixo.
        String restored = "console=ttyAMA0,115200 earlycon rdinit=/init";
        byte[] roundTrip = FdtPatcher.withBootargs(patched, restored);
        assertHeaderConsistent(roundTrip);
        assertContainsAsciiOnce(roundTrip, restored + "\0");
    }

    @Test
    void patchesMemoryRegPlaceholderToRealSize() throws IOException {
        assumeTrue(Files.exists(DTB), "asset real ausente nesta checkout");
        byte[] original = Files.readAllBytes(DTB);
        long ramSizeBytes = 256L * 1024 * 1024;

        byte[] patched = FdtPatcher.withMemorySize(original, ramSizeBytes);

        // mesmo tamanho de arquivo: reg tem sempre 2 células de 32 bits, sem realocação.
        assertEquals(original.length, patched.length);
        assertHeaderConsistent(patched);
        byte[] expectedReg = {0, 0, 0, 0, 0x10, 0, 0, 0}; // {0x0, 0x10000000} big-endian.
        assertTrue(indexOf(patched, expectedReg) >= 0, "reg patcheado não encontrado no blob");
    }

    @Test
    void bothPatchesComposeInEitherOrder() throws IOException {
        assumeTrue(Files.exists(DTB), "asset real ausente nesta checkout");
        byte[] original = Files.readAllBytes(DTB);
        String cmdline = "console=ttyAMA0,115200 earlycon rdinit=/init";

        byte[] bootargsThenMemory =
                FdtPatcher.withMemorySize(FdtPatcher.withBootargs(original, cmdline), 128L * 1024 * 1024);
        byte[] memoryThenBootargs =
                FdtPatcher.withBootargs(FdtPatcher.withMemorySize(original, 128L * 1024 * 1024), cmdline);

        assertHeaderConsistent(bootargsThenMemory);
        assertHeaderConsistent(memoryThenBootargs);
        assertContainsAsciiOnce(bootargsThenMemory, cmdline + "\0");
        assertContainsAsciiOnce(memoryThenBootargs, cmdline + "\0");
    }

    @Test
    void createsInitrdRangePropertiesThatDoNotExistYet() throws IOException {
        assumeTrue(Files.exists(DTB), "asset real ausente nesta checkout");
        byte[] original = Files.readAllBytes(DTB);
        long start = 0x1000_0000L;
        long end = 0x1010_0000L;

        byte[] patched = FdtPatcher.withInitrdRange(original, start, end);

        assertHeaderConsistent(patched);
        byte[] expectedStart = {0x10, 0, 0, 0};
        byte[] expectedEnd = {0x10, 0x10, 0, 0};
        assertTrue(indexOf(patched, expectedStart) >= 0, "linux,initrd-start não encontrado no blob");
        assertTrue(indexOf(patched, expectedEnd) >= 0, "linux,initrd-end não encontrado no blob");
        assertContainsAsciiOnce(patched, "linux,initrd-start\0");
        assertContainsAsciiOnce(patched, "linux,initrd-end\0");
        assertTrue(patched.length > original.length,
                "propriedades novas devem crescer o blob (nada foi sobrescrito)");
    }

    @Test
    void composesWithBootargsAndMemorySizePatches() throws IOException {
        assumeTrue(Files.exists(DTB), "asset real ausente nesta checkout");
        byte[] original = Files.readAllBytes(DTB);
        String cmdline = "console=ttyAMA0,115200 earlycon root=/dev/ram rdinit=/init";

        byte[] patched = FdtPatcher.withInitrdRange(
                FdtPatcher.withMemorySize(FdtPatcher.withBootargs(original, cmdline), 128L * 1024 * 1024),
                0x1000_0000L, 0x1010_0000L);

        assertHeaderConsistent(patched);
        assertContainsAsciiOnce(patched, cmdline + "\0");
        assertContainsAsciiOnce(patched, "linux,initrd-start\0");
        assertContainsAsciiOnce(patched, "linux,initrd-end\0");
    }

    @Test
    void rejectsInvalidMagic() {
        byte[] garbage = new byte[64];
        assertThrows(IllegalArgumentException.class, () -> FdtPatcher.withBootargs(garbage, "x"));
    }

    /// `totalsize`/`size_dt_struct`/`off_dt_strings` do cabeçalho precisam continuar batendo com
    /// o comprimento real do array e com o início real do bloco de strings — a checagem mais
    /// forte de que a emenda (splice) não corrompeu os deslocamentos.
    private static void assertHeaderConsistent(byte[] dtb) {
        int totalSize = readWord(dtb, 4);
        int offDtStruct = readWord(dtb, 8);
        int offDtStrings = readWord(dtb, 12);
        int sizeDtStrings = readWord(dtb, 32);
        int sizeDtStruct = readWord(dtb, 36);
        assertEquals(dtb.length, totalSize, "totalsize não bate com o tamanho real do array");
        assertEquals(offDtStrings, offDtStruct + sizeDtStruct,
                "off_dt_strings não é mais o fim do bloco de estrutura");
        assertEquals(totalSize, offDtStrings + sizeDtStrings,
                "bloco de strings não termina mais no fim do arquivo");
    }

    private static void assertContainsAsciiOnce(byte[] dtb, String text) {
        byte[] needle = text.getBytes(StandardCharsets.US_ASCII);
        int first = indexOf(dtb, needle);
        assertTrue(first >= 0, "'" + text + "' não encontrado no FDT patcheado");
    }

    private static void assertTrue(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertTrue(condition, message);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static int readWord(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }
}
