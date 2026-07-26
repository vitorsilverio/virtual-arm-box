package dev.vitorsilverio.linuxbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Aceite PARCIAL da task B4.1.5 (ver relatório da sessão e `testdata/README.md`): o objetivo
/// completo era o kernel `testdata/vmlinuz-3.2.0-4-versatile` bootar até um shell `busybox`
/// interativo nos backends interpretado E ASM/JIT, com `--check` sobrevivendo ao boot inteiro
/// (G1). **Isso NÃO foi alcançado nesta sessão** — o boot trava mais cedo, numa falta de
/// tradução recursiva ao redor da página de vetores de exceção (`0xffff0000`, vetores altos)
/// logo após o kernel saltar para código em endereço virtual pós-MMU (`0xc0...`), causa raiz
/// ainda não isolada (não é falta de decode conhecida — `PLD`/`PRELOAD_HINTS`, o único gap real
/// de decode encontrado e corrigido nesta sessão, já não é mais o bloqueio).
///
/// Este teste verifica o marco real e verificável que FOI alcançado antes desse bloqueio: o
/// zImage descomprime e o processador salta para o kernel descomprimido (mensagem
/// `"Uncompressing Linux... done, booting the kernel."`, impressa pelo próprio stub de
/// descompressão do zImage real via UART0) — prova que RAM ({@link VersatilePbMachine},
/// `PagedAddressSpace`), PL011, MMU (`TranslatingAddressSpace`/`Cp15VmsaCoprocessor`,
/// B4.1.1-B4.1.4) e o protocolo de boot ATAGs (`AtagsBuilder`) funcionam de ponta a ponta com um
/// kernel Linux ARM REAL, nos dois backends.
///
/// **Não usa `--check` aqui**: é ordens de magnitude mais lento (interpretado + ASM em paralelo,
/// save/restore de estado por bloco) e o boot completo (o único cenário que valeria testar com
/// `--check`) ainda não é alcançável — descartado deliberadamente até o boot avançar mais, para
/// não deixar a suíte de testes com um teste de minutos sem um marco novo para provar.
class VersatilePbBootTest {
    private static final Path TESTDATA = Path.of("testdata");
    private static final String CMDLINE = "console=ttyAMA0 root=/dev/ram rdinit=/init";
    /// Fatias suficientes para atravessar a descompressão do zImage (medido experimentalmente
    /// nesta sessão: a mensagem aparece por volta da fatia 360554 em ambos os backends), com
    /// folga generosa.
    private static final int MAX_SLICES = 600_000;
    private static final String DECOMPRESSION_DONE_MARKER = "done, booting the kernel.";

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void decompressesKernelAndJumpsToItInterpreted() throws Exception {
        String console = bootAndCapture(VersatilePbMachine.Backend.INTERPRETED);
        assertReachedDecompressionCheckpoint(console);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void decompressesKernelAndJumpsToItJit() throws Exception {
        String console = bootAndCapture(VersatilePbMachine.Backend.JIT);
        assertReachedDecompressionCheckpoint(console);
    }

    private static void assertReachedDecompressionCheckpoint(String console) {
        assertTrue(console.contains(DECOMPRESSION_DONE_MARKER),
                "esperava o zImage terminar de descomprimir e saltar para o kernel, obtive:\n" + console);
    }

    private static String bootAndCapture(VersatilePbMachine.Backend backend) throws Exception {
        byte[] kernel = Files.readAllBytes(TESTDATA.resolve("vmlinuz-3.2.0-4-versatile"));
        byte[] initramfs = Files.readAllBytes(TESTDATA.resolve("initramfs.cpio.gz"));
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        VersatilePbMachine machine = VersatilePbMachine.create(kernel, initramfs, CMDLINE, console, backend);
        for (int i = 0; i < MAX_SLICES; i++) {
            machine.runSlice();
            if (i % 2000 == 0 && console.toString(java.nio.charset.StandardCharsets.US_ASCII)
                    .contains(DECOMPRESSION_DONE_MARKER)) {
                break;
            }
        }
        return console.toString(java.nio.charset.StandardCharsets.US_ASCII);
    }
}
