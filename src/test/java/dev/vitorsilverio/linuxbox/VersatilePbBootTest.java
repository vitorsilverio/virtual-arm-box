package dev.vitorsilverio.linuxbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Aceite da task B4.1.5: o kernel Linux REAL de `testdata/vmlinuz-3.2.0-4-versatile` boota neste
/// hospedeiro até um **shell `busybox` interativo**, nos backends interpretado E ASM/JIT — e o
/// shell responde de verdade a um comando digitado (`echo`), não só imprime o prompt.
///
/// O caminho exercitado de ponta a ponta é o épico B4.1 inteiro: `PagedAddressSpace` (RAM) +
/// PL011/SP804/PL190 + protocolo de boot ATAGs (`AtagsBuilder`) + MMU real
/// (`TranslatingAddressSpace`/`Cp15VmsaCoprocessor`, B4.1.1-B4.1.4) com page-walk, aborts precisos
/// (demand paging e copy-on-write do `fork()` do kernel), TLB de instrução/dados e gerações de
/// tradução no `BlockCache`.
///
/// **Não usa `--check` aqui**: rodar interpretado e ASM em paralelo com save/restore de estado por
/// bloco é ordens de magnitude mais lento que os dois backends já cobertos, e o boot completo leva
/// minutos mesmo sem isso — descartado deliberadamente para não deixar a suíte com um teste de
/// dezenas de minutos que não prova nada além do que estes dois já provam separadamente.
class VersatilePbBootTest {
    private static final Path TESTDATA = Path.of("testdata");
    private static final String CMDLINE = "console=ttyAMA0 root=/dev/ram rdinit=/init";
    /// Fatias suficientes para atravessar descompressão + boot do kernel + `busybox --install` +
    /// shell, com folga generosa (o laço para assim que o marco aparece).
    private static final int MAX_SLICES = 8_000_000;
    /// Prompt do `sh` do busybox rodando como root.
    private static final String SHELL_PROMPT = "/ #";
    /// Comando digitado no shell. As aspas no meio existem de propósito: a linha ECOADA pelo tty
    /// contém `LINUX"BOX-SHELL-OK"`, e só a SAÍDA do comando contém `LINUXBOX-SHELL-OK` sem elas —
    /// sem isso o teste passaria só com o eco do terminal, sem o shell ter executado nada.
    private static final String SHELL_COMMAND = "echo LINUX\"BOX-SHELL-OK\"\n";
    private static final String SHELL_COMMAND_OUTPUT = "LINUXBOX-SHELL-OK";
    /// De quantas em quantas fatias o console é reexaminado (converter o buffer inteiro a cada
    /// fatia domina o tempo do teste).
    private static final int CONSOLE_POLL_INTERVAL = 2_000;
    /// Fatias rodadas entre dois bytes digitados — ver {@link #type}.
    private static final int SLICES_PER_TYPED_BYTE = 200;

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void bootsToInteractiveBusyboxShellInterpreted() throws Exception {
        assertReachesInteractiveShell(VersatilePbMachine.Backend.INTERPRETED);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void bootsToInteractiveBusyboxShellJit() throws Exception {
        assertReachesInteractiveShell(VersatilePbMachine.Backend.JIT);
    }

    private static void assertReachesInteractiveShell(VersatilePbMachine.Backend backend) throws Exception {
        byte[] kernel = Files.readAllBytes(TESTDATA.resolve("vmlinuz-3.2.0-4-versatile"));
        byte[] initramfs = Files.readAllBytes(TESTDATA.resolve("initramfs.cpio.gz"));
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        VersatilePbMachine machine = VersatilePbMachine.create(kernel, initramfs, CMDLINE, console, backend);

        boolean reachedPrompt = runUntil(machine, console, SHELL_PROMPT);
        assertTrue(reachedPrompt,
                "esperava o prompt do shell busybox (" + SHELL_PROMPT + "), obtive:\n" + text(console));

        // A contagem de referência sai ANTES de digitar: `type` já roda fatias, e a resposta pode
        // aparecer durante a própria digitação (foi o que aconteceu na 1ª versão deste teste, que
        // reprovava um boot perfeitamente bem-sucedido).
        int outputsBeforeTyping = occurrences(text(console), SHELL_COMMAND_OUTPUT);
        type(machine, SHELL_COMMAND);
        assertTrue(runUntilMoreThan(machine, console, SHELL_COMMAND_OUTPUT, outputsBeforeTyping),
                "o shell não respondeu ao comando digitado, obtive:\n" + text(console));
    }

    /// Digita `text` no UART0 no ritmo de um byte por bloco de fatias. **Não dá para empurrar a
    /// linha inteira de uma vez**: o FIFO de recepção do PL011 tem 16 posições (`hw/char/pl011.c`)
    /// e hardware real DESCARTA o excedente — despejar 25 bytes de uma vez faz o guest receber só
    /// os 16 primeiros, sem o `\n` (foi exatamente o que aconteceu na primeira versão deste teste).
    private static void type(VersatilePbMachine machine, String text) {
        for (byte typed : text.getBytes(StandardCharsets.US_ASCII)) {
            machine.typeByte(typed & 0xFF);
            for (int slice = 0; slice < SLICES_PER_TYPED_BYTE; slice++) {
                machine.runSlice();
            }
        }
    }

    /// Roda fatias até `marker` aparecer no console MAIS vezes do que já aparecia na entrada
    /// (o prompt reaparece a cada comando, então "conter" não basta).
    private static boolean runUntil(VersatilePbMachine machine, ByteArrayOutputStream console, String marker) {
        return runUntilMoreThan(machine, console, marker, occurrences(text(console), marker));
    }

    private static boolean runUntilMoreThan(VersatilePbMachine machine, ByteArrayOutputStream console,
                                            String marker, int before) {
        if (occurrences(text(console), marker) > before) {
            return true;
        }
        for (int slice = 0; slice < MAX_SLICES; slice++) {
            machine.runSlice();
            if (slice % CONSOLE_POLL_INTERVAL == 0 && occurrences(text(console), marker) > before) {
                return true;
            }
        }
        return false;
    }

    private static int occurrences(String console, String marker) {
        int count = 0;
        for (int at = console.indexOf(marker); at >= 0; at = console.indexOf(marker, at + marker.length())) {
            count++;
        }
        return count;
    }

    private static String text(ByteArrayOutputStream console) {
        return console.toString(StandardCharsets.US_ASCII);
    }
}
