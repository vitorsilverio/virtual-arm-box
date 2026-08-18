package dev.vitorsilverio.virtualarmbox.boot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Testa {@link FdtPatcher#withNodeRemoved} contra o `.dtb` REAL de `testdata/raspi3-64/`
/// (task F11) — o Raspberry Pi 3 real (quad-core) exige remover `cpu@1`/`cpu@2`/`cpu@3` de
/// verdade (não só `status = "disabled"`, ver Javadoc de {@link FdtPatcher#withNodeRemoved})
/// para rodar com um único núcleo sem nenhum SMP bring-up.
class FdtPatcherNodeRemovalTest {
    private static final Path DTB = Path.of("testdata", "raspi3-64", "bcm2710-rpi-3-b.dtb");

    @Test
    void removesLeafCpuNodeAndShrinksBlob() throws IOException {
        assumeTrue(Files.exists(DTB), "asset real ausente nesta checkout");
        byte[] original = Files.readAllBytes(DTB);

        byte[] patched = FdtPatcher.withNodeRemoved(original, "cpu@1");

        assertHeaderConsistent(patched);
        assertTrue(patched.length < original.length,
                "blob deveria encolher: o nó inteiro (não só uma propriedade) foi removido");
        // Achado real: o .dtb tem um nó /aliases com `cpu1 = "/cpus/cpu@1"` (referência por
        // CAMINHO, valor de STRING) — essa ocorrência de "cpu@1" é legítima e não deve
        // desaparecer (withNodeRemoved só mexe na subárvore do próprio /cpus/cpu@1, não em
        // quem o referencia por nome). A checagem forte é o TOKEN `FDT_BEGIN_NODE` do nó em si
        // (4 bytes `00000001` imediatamente seguidos do nome) — isso sim precisa sumir.
        byte[] beginNodeToken = beginNodeTokenFor("cpu@1");
        assertTrue(indexOf(original, beginNodeToken) >= 0,
                "sanity check: o token FDT_BEGIN_NODE de cpu@1 precisa existir no .dtb original");
        assertTrue(indexOf(patched, beginNodeToken) < 0,
                "o token FDT_BEGIN_NODE de cpu@1 não deveria mais existir após a remoção");
    }

    @Test
    void removingAllThreeExtraCpusLeavesOnlyCpuZero() throws IOException {
        assumeTrue(Files.exists(DTB), "asset real ausente nesta checkout");
        byte[] original = Files.readAllBytes(DTB);

        byte[] patched = FdtPatcher.withNodeRemoved(
                FdtPatcher.withNodeRemoved(
                        FdtPatcher.withNodeRemoved(original, "cpu@1"),
                        "cpu@2"),
                "cpu@3");

        assertHeaderConsistent(patched);
        assertTrue(indexOf(patched, beginNodeTokenFor("cpu@0")) >= 0,
                "cpu@0 deveria continuar presente");
        assertTrue(indexOf(patched, beginNodeTokenFor("cpu@1")) < 0);
        assertTrue(indexOf(patched, beginNodeTokenFor("cpu@2")) < 0);
        assertTrue(indexOf(patched, beginNodeTokenFor("cpu@3")) < 0);
        // cpu@0 ainda pode ser lido/patcheado normalmente depois da remoção dos irmãos —
        // prova que a árvore continua bem formada (não só o header consistente).
        byte[] stillPatchable = FdtPatcher.withNodeDisabled(patched, "cpu@0");
        assertHeaderConsistent(stillPatchable);
    }

    @Test
    void removedNodePropertiesAreGoneToo() throws IOException {
        assumeTrue(Files.exists(DTB), "asset real ausente nesta checkout");
        byte[] original = Files.readAllBytes(DTB);

        // achado de bordo real: cpu@1 tem `reg = <0x1>` (célula de endereço do nó) — depois de
        // remover o nó inteiro, sobrescrever uma propriedade SUA deve falhar (o nó não existe
        // mais), provando que a remoção não deixou "restos" de propriedade patcheáveis.
        byte[] patched = FdtPatcher.withNodeRemoved(original, "cpu@1");

        assertThrows(IllegalArgumentException.class,
                () -> FdtPatcher.withNodeDisabled(patched, "cpu@1"));
    }

    @Test
    void rejectsRemovingNodeThatDoesNotExist() throws IOException {
        assumeTrue(Files.exists(DTB), "asset real ausente nesta checkout");
        byte[] original = Files.readAllBytes(DTB);

        assertThrows(IllegalArgumentException.class,
                () -> FdtPatcher.withNodeRemoved(original, "cpu@99"));
    }

    @Test
    void removalComposesWithPropertyPatchesAfterward() throws IOException {
        assumeTrue(Files.exists(DTB), "asset real ausente nesta checkout");
        byte[] original = Files.readAllBytes(DTB);
        String cmdline = "console=ttyAMA0,115200 earlycon root=/dev/ram rdinit=/init";

        byte[] withoutExtraCpus = FdtPatcher.withNodeRemoved(
                FdtPatcher.withNodeRemoved(
                        FdtPatcher.withNodeRemoved(original, "cpu@1"),
                        "cpu@2"),
                "cpu@3");
        byte[] fullyPatched = FdtPatcher.withMemorySize(
                FdtPatcher.withBootargs(withoutExtraCpus, cmdline), 512L * 1024 * 1024);

        assertHeaderConsistent(fullyPatched);
        assertTrue(containsAscii(fullyPatched, cmdline + "\0"));
    }

    /// Mesma checagem forte de `FdtPatcherTest` (cabeçalho batendo com o tamanho real e com os
    /// deslocamentos do bloco de strings) — duplicada aqui (não reaproveitada via `import static`
    /// de outra classe de teste) para manter cada arquivo de teste autocontido, mesmo padrão já
    /// usado pelas outras classes de teste deste pacote.
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

    private static boolean containsAscii(byte[] dtb, String text) {
        byte[] needle = text.getBytes(StandardCharsets.US_ASCII);
        return indexOf(dtb, needle) >= 0;
    }

    /// Constrói o padrão de bytes exato do token `FDT_BEGIN_NODE` (`0x00000001`) seguido
    /// imediatamente do nome do nó (terminado em NUL) — só isso identifica, sem ambiguidade, a
    /// DEFINIÇÃO do nó em si, ao contrário de uma simples busca pela string do nome (que também
    /// bate em referências por CAMINHO dentro de valores de propriedade, ex. `/aliases`, ver
    /// achado real documentado em {@link #removesLeafCpuNodeAndShrinksBlob}).
    private static byte[] beginNodeTokenFor(String nodeName) {
        byte[] name = (nodeName + "\0").getBytes(StandardCharsets.US_ASCII);
        byte[] token = new byte[Integer.BYTES + name.length];
        token[3] = 0x01; // FDT_BEGIN_NODE, big-endian.
        System.arraycopy(name, 0, token, Integer.BYTES, name.length);
        return token;
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
