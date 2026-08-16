package dev.vitorsilverio.virtualarmbox;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Aceite da task F3 (`--machine=raspi1`) — ver `tasks/trilha-f-infra/f3-raspi1-machine.md`.
///
/// **M1 redefinido (sessão 1/3)**: a mensagem literal do enunciado ("Uncompressing Linux...
/// done, booting the kernel.") não existe neste `kernel.img` oficial — confirmado rodando o
/// MESMO `kernel.img`+`.dtb` no `qemu-system-arm -M raspi1ap` (oráculo instalado nesta máquina)
/// como referência. O marcador equivalente adotado é `Booting Linux on physical CPU 0x0` via
/// `earlycon` — ver `testdata/raspi1/README.md`.
///
/// **Sessão 2/3 — o bloqueio de desempenho da sessão 1 foi FECHADO**: descomprimir o
/// `kernel.img` no HOST ({@link dev.vitorsilverio.virtualarmbox.boot.ZImageDecompressor}) e
/// carregar a imagem já pronta direto no endereço de link (`stext`) elimina o `inflate()` caro
/// do guest (~750 milhões de ciclos medidos na sessão 1). Isso destravou o boot para progredir
/// centenas de milhares de ciclos a mais, revelando (e permitindo corrigir) DOIS bugs reais e
/// arquiteturais do `arm-jitter` no `Cp15VmsaCoprocessor`/`Bcm2835Cp15Extras` — primeira
/// validação de sistema real do `ARM11_MPCORE`/ARMv6K (ver Javadoc de {@link Bcm2835Machine} e
/// de `Cp15VmsaCoprocessor`/`Bcm2835Cp15Extras` no `arm-jitter`):
/// 1. `MCR p15,0,Rt,c13,c0,3` (`TPIDRURO`, ponteiro de TLS) não era reconhecido — UNDEFINED tão
///    cedo no boot que os vetores de exceção ainda não tinham sido copiados por
///    `early_trap_init()`, cascateando num laço infinito de `PREFETCH_ABORT` (busca da PRÓPRIA
///    rotina de vetor também falhava). Corrigido: `c13,c0,{0,2,3,4}` (FCSEIDR/TPIDRURW/
///    TPIDRURO/TPIDRPRW) agora são armazenamento simples, sem efeito colateral.
/// 2. `ID_MMFR0`/`ID_ISAR*`/qualquer sub-registrador `c0` (esquema CPUID ARMv6+) fora de
///    `MIDR`/`CTR` também não era reconhecido pelo mesmo motivo. Corrigido de forma
///    arquiteturalmente correta (não um palpite): a ARM GARANTE que ler um sub-registrador de ID
///    não alocado devolve um valor UNKNOWN (aqui `0`), NUNCA lança UNDEFINED — `Bcm2835Cp15Extras`
///    agora reivindica o esquema `c0`/`opcode1=0` inteiro em vez de listar `CRm` um a um.
///
/// **Sessão 2/3 — M1 NÃO fechou naquela sessão**: depois dos dois fixes de CP15 acima, o boot
/// esbarrava num limite deliberado e já documentado do `arm-jitter`: `IrExecutionSupport.
/// checkLittleEndianData` recusava (`UnsupportedOperationException`, de propósito) qualquer
/// acesso a dado com `CPSR.E=1` (big-endian/`SETEND BE`) — decisão de escopo MVP da task `B1.5`
/// do `arm-jitter` (só little-endian). O kernel ARMv6K real executa `SETEND`/toca dado
/// big-endian bem cedo no boot.
///
/// **Sessão 3/3 (2026-08-15) — BLOQUEIO DE BE8 FECHADO pela task `B1.8` do `arm-jitter`
/// (sessão dedicada, `.m2` local já publicado com o fix) — M1 FECHOU DE VERDADE, nos DOIS
/// backends**: {@link #reachesEarlyconBannerAcceiteM1Interpreted()} e
/// {@link #reachesEarlyconBannerAcceiteM1Jit()} passam em menos de 1s cada (o marcador aparece
/// bem cedo no log). Nenhum bug novo do `arm-jitter` apareceu nesta sessão além do que a B1.8 já
/// tinha corrigido.
///
/// **M2 NÃO fechou nesta sessão — bloqueio NOVO, genuinamente diferente de BE8/CP15/desempenho**:
/// com M1 destravado, o boot avança bem além do `earlycon` mas entra num LAÇO DE `Oops` do
/// próprio kernel (`Unable to handle kernel paging request`, "8&lt;--- cut here ---" repetido)
/// já em `unflatten_device_tree()`/`fdt_next_tag` (parsing do FDT via a janela de `fixmap`
/// mapeada por virtual, logo depois do scan físico inicial que já funcionou — "Machine model:
/// Raspberry Pi Model B", "Reserved memory: created CMA memory pool..." aparecem certinhos antes
/// do loop começar), a poucas dezenas de milhares de instruções do banner do M1.
///
/// **Confirmado como divergência REAL via o oráculo QEMU 8.0.0** (`qemu-system-arm -M raspi1ap`,
/// EXATAMENTE o mesmo `kernel.img`+`bcm2708-rpi-b.dtb`+`initramfs.cpio.gz`+cmdline desta classe):
/// o QEMU boota limpo até enumerar USB (`dwc_otg`/`smsc95xx`) e monta o initramfs
/// (`Trying to unpack rootfs image as initramfs...` / `Freeing initrd memory`), MUITO além de
/// `Freeing unused kernel memory` — sem NENHUM Oops. Isto não é uma feature faltando (como o
/// BE8 era): é uma divergência de comportamento observável entre este emulador e uma referência
/// de hardware real para a MESMA entrada, ou seja, um bug real em algum lugar (`arm-jitter` ou
/// `virtual-arm-box` — root cause NÃO isolado ainda).
///
/// **Sessão extra (2026-08-16) — lacuna de observabilidade FECHADA, causa raiz AINDA NÃO
/// isolada, mas duas hipóteses da sessão anterior foram DESCARTADAS com evidência concreta**:
///
/// 1. **Lacuna de observabilidade fechada** (task `E2` do `arm-jitter`,
///    `ArmTraceListener#onMemoryAbort`, aditivo/G3): antes, `beforeInstruction`/`afterInstruction`
///    só disparavam sob {@link dev.vitorsilverio.armjitter.core.ArmCore#step()} — sob
///    {@link dev.vitorsilverio.armjitter.core.ArmCore#runBlocks} (o caminho real de
///    {@link Bcm2835Machine#runSlice()}) nenhum evento por-instrução disparava, então não dava
///    pra correlacionar o texto do Oops já impresso com a instrução exata que faltou. O novo
///    gancho dispara em {@code ArmCore#enterMemoryAbort} — o único ponto de convergência dos 3
///    caminhos de execução (`step()`, bloco interpretado, bloco compilado/JIT) — com o PC exato
///    ANTES de qualquer mutação de estado. Com ele instalado (harness temporário, removido antes
///    do commit): **o primeiro fault reporta `pc=0xc0a69088`, que bate byte-a-byte com o que o
///    próprio kernel imprime no Oops (`PC is at fdt_next_tag+0xec/0x154`)** — confirma que o
///    caminho de execução real (interpretado, backend `INTERPRETED` desta suíte) está consistente
///    com o texto do console; não havia divergência de instrumentação, só falta de instrumentação.
/// 2. **Hipótese (a) da sessão anterior (staleness de TLB/PTE do `TranslatingAddressSpace`)
///    DESCARTADA**: o `walk()` da MMU sempre re-lê `physical.read32(ttbr0Base + l1Index*4)` — sem
///    cache de L1 — então o valor que a tradução vê É o mesmo que o próprio kernel lê ao imprimir
///    `*pgd=0800000e(bad)` no diagnóstico do Oops (confirmado por leitura do código-fonte de
///    `TranslatingAddressSpace#walk`/`walkSection`/`walkCoarsePage`: nenhum dos dois caminhos usa
///    o resultado cacheado da `MicroTlb` para decidir o TIPO do descritor L1, só para o PPN depois
///    de já validado). Não é uma questão de visibilidade/cache — o conteúdo físico real da RAM do
///    guest naquele slot de PGD genuinamente é um descritor de SEÇÃO, não um ponteiro de tabela.
/// 3. **Hipótese (b) da sessão anterior (tamanho de RAM 256MiB vs. ~448MiB do QEMU) TESTADA E
///    DESCARTADA**: com `Bcm2835Machine.RAM_SIZE_BYTES` temporariamente elevado para 512MiB
///    (experimento revertido antes do commit — não é uma mudança permanente), o boot produz o
///    MESMO fault, no MESMO PC (`0xc0a69088`), no MESMO endereço virtual (`0xff8ae000`), com o
///    MESMO conteúdo de PGD (`0800000e`) — só a reserva de CMA mudou de endereço físico
///    (`0x0c000000`→`0x1c000000`, proporcional à RAM maior, como esperado). O tamanho de RAM não
///    influencia este bug.
/// 4. **Hipótese nova, mais específica, com evidência concreta (NÃO confirmada, é a MELHOR pista
///    até agora)**: os registradores do Oops mostram `r5=0xff8ac000` (provavelmente a base da
///    janela `fixmap` mapeada para o FDT) e o endereço que falta é `r0=0xff8ae000` — exatamente
///    `r5 + 0x2000` (dois "passos" de página adiante da base da janela). Isso é consistente com
///    `fdt_next_tag()` andando sequencialmente pela estrutura do FDT e ultrapassando o fim de uma
///    janela `fixmap` mapeada MENOR do que o `totalsize` real do `.dtb` patcheado por
///    {@link dev.vitorsilverio.virtualarmbox.boot.FdtPatcher} — ou seja, uma variante mais precisa
///    da antiga hipótese (c): não é que o `.dtb` esteja corrompido (`FdtPatcherTest` cobre
///    round-trip), é que o número de páginas que `fixmap_remap_fdt()` decide mapear (calculado a
///    partir do `totalsize` do cabeçalho FDT que ele lê) pode não cobrir o `totalsize` real depois
///    do patch de `/memory@0/reg` e `/chosen/bootargs` (que podem CRESCER o blob). Também é
///    compatível com o próprio texto do Oops: o `pgd` mostra um descritor de SEÇÃO (não uma
///    tabela de 2º nível) no slot L1 que cobre `0xff800000`-`0xff8fffff` inteiro — se
///    `fixmap_remap_fdt()` nunca chamou `alloc_init_pte` pra esse slot (porque calculou menos
///    páginas do que precisava), o slot fica com QUALQUER lixo/valor pré-existente de
///    `swapper_pg_dir`, que pode legitimamente parecer uma seção. **Não confirmado**: não foi
///    possível, dentro desta sessão, ler a RAM física bruta do guest (fora da MMU) para provar se
///    o slot de PGD nunca foi escrito vs. foi escrito e depois sobrescrito — ambos os cenários
///    produzem o mesmo sintoma observável.
///
/// **Próximo passo recomendado, concreto**: instrumentar (ou usar o `onMemoryAbort` já existente
/// combinado com leitura direta do `PagedAddressSpace` físico, sem passar pela MMU) o slot de PGD
/// em `ttbr0Base + 4088*4` (`0xff8ae000 >>> 20 == 4088`, `TTBR0` reportado como `0x00004008` →
/// `ttbr0Base=0x4000`) a cada `slice`, pra determinar se ele é escrito alguma vez antes do fault
/// (nunca escrito = bug de cálculo de páginas do `fixmap_remap_fdt()`/patch do `totalsize`;
/// escrito e depois sobrescrito = corrupção genuína, aponta para outro lugar, ex. sobreposição de
/// endereço físico entre kernel/initrd/dtb/heap early). Comparar também o `totalsize` do cabeçalho
/// FDT ANTES e DEPOIS do patch de {@link dev.vitorsilverio.virtualarmbox.boot.FdtPatcher} contra o
/// tamanho real do array de bytes devolvido, byte a byte.
///
/// Este achado continua sendo do tipo "motivo genuinamente novo" que a task F3 instrui a
/// documentar e PARAR, não improvisar um fix às cegas: não é BE8 (fechado pela B1.8), não é CP15
/// faltante (fechado na sessão 2/3), não é desempenho (fechado na sessão 2/3 pelo
/// `ZImageDecompressor`), não é staleness de TLB/MMU (descartado nesta sessão) e não é tamanho de
/// RAM (descartado nesta sessão). M2/M3 continuam `@Disabled`.
///
/// {@link #smokeTestBootsWithoutException()} prova que a infraestrutura desta task
/// (CP15/MMU/periféricos/`FdtPatcher`/`ZImageDecompressor`/handoff) está correta hoje.
class Raspi1BootTest {
    private static final Path TESTDATA = Path.of("testdata", "raspi1");
    private static final String CMDLINE = "console=ttyAMA0,115200 earlycon root=/dev/ram rdinit=/init";
    private static final String EARLYCON_BANNER = "Booting Linux on physical CPU";
    private static final String FREEING_KERNEL_MEMORY = "Freeing unused kernel memory";
    private static final String SHELL_PROMPT = "/ #";
    private static final String SHELL_COMMAND = "echo RASPI\"1-SHELL-OK\"\n";
    private static final String SHELL_COMMAND_OUTPUT = "RASPI1-SHELL-OK";

    private static final int MAX_SLICES = 8_000_000;
    private static final int CONSOLE_POLL_INTERVAL = 2_000;
    private static final int SLICES_PER_TYPED_BYTE = 200;

    /// Fatias suficientes para atravessar o `head.S` inicial sem lançar/travar — não tenta
    /// chegar a nenhum marco, só prova que a infra está correta
    /// (RAM/CP15/MMU/periféricos/handoff/descompressão no host). Deliberadamente conservador:
    /// mais fatias alcançam o limite de `CPSR.E=1`/big-endian documentado no Javadoc da classe
    /// (não é um teto desta task, é uma decisão de escopo já tomada no `arm-jitter`, task
    /// `B1.5`).
    private static final int SMOKE_SLICES = 60;

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void smokeTestBootsWithoutException() throws Exception {
        assumeTrue(Files.exists(TESTDATA.resolve("kernel.img")), "assets reais ausentes nesta checkout");
        Bcm2835Machine machine = load(Bcm2835Machine.Backend.INTERPRETED, new ByteArrayOutputStream());

        int initialPc = machine.core().programCounter();
        assertEquals(0x0000_8000, initialPc, "entrada esperada no endereço de link do stext descomprimido");

        for (int slice = 0; slice < SMOKE_SLICES; slice++) {
            machine.runSlice();
        }

        assertTrue(machine.core().cycles() > 0, "nenhum ciclo executado");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void reachesEarlyconBannerAcceiteM1Interpreted() throws Exception {
        assertReachesMarker(Bcm2835Machine.Backend.INTERPRETED, EARLYCON_BANNER);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void reachesEarlyconBannerAcceiteM1Jit() throws Exception {
        assertReachesMarker(Bcm2835Machine.Backend.JIT, EARLYCON_BANNER);
    }

    @Disabled("M2 não fecha nesta sessão: laço de Oops NOVO (não BE8/CP15/desempenho) em "
            + "unflatten_device_tree/fdt_next_tag, confirmado divergente do QEMU 8.0.0 como oráculo "
            + "(mesmo kernel+dtb+initramfs). Causa raiz NÃO isolada — ver Javadoc da classe.")
    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void reachesFreeingKernelMemoryAcceiteM2Interpreted() throws Exception {
        assertReachesMarker(Bcm2835Machine.Backend.INTERPRETED, FREEING_KERNEL_MEMORY);
    }

    @Disabled("M2 não fecha nesta sessão — ver Javadoc da classe.")
    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void reachesFreeingKernelMemoryAcceiteM2Jit() throws Exception {
        assertReachesMarker(Bcm2835Machine.Backend.JIT, FREEING_KERNEL_MEMORY);
    }

    @Disabled("M3 depende de M1/M2 — ver Javadoc da classe.")
    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void bootsToInteractiveBusyboxShellAcceiteM3Interpreted() throws Exception {
        assertReachesInteractiveShell(Bcm2835Machine.Backend.INTERPRETED);
    }

    @Disabled("M3 depende de M1/M2 — ver Javadoc da classe.")
    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void bootsToInteractiveBusyboxShellAcceiteM3Jit() throws Exception {
        assertReachesInteractiveShell(Bcm2835Machine.Backend.JIT);
    }

    private static void assertReachesMarker(Bcm2835Machine.Backend backend, String marker) throws Exception {
        assumeTrue(Files.exists(TESTDATA.resolve("kernel.img")), "assets reais ausentes nesta checkout");
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        Bcm2835Machine machine = load(backend, console);

        boolean reached = runUntil(machine, console, marker);
        assertTrue(reached, "esperava '" + marker + "' no console, obtive:\n" + text(console));
    }

    private static void assertReachesInteractiveShell(Bcm2835Machine.Backend backend) throws Exception {
        assumeTrue(Files.exists(TESTDATA.resolve("kernel.img")), "assets reais ausentes nesta checkout");
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        Bcm2835Machine machine = load(backend, console);

        boolean reachedPrompt = runUntil(machine, console, SHELL_PROMPT);
        assertTrue(reachedPrompt,
                "esperava o prompt do shell busybox (" + SHELL_PROMPT + "), obtive:\n" + text(console));

        int outputsBeforeTyping = occurrences(text(console), SHELL_COMMAND_OUTPUT);
        type(machine, SHELL_COMMAND);
        assertTrue(runUntilMoreThan(machine, console, SHELL_COMMAND_OUTPUT, outputsBeforeTyping),
                "o shell não respondeu ao comando digitado, obtive:\n" + text(console));
    }

    private static Bcm2835Machine load(Bcm2835Machine.Backend backend, ByteArrayOutputStream console)
            throws Exception {
        byte[] kernel = Files.readAllBytes(TESTDATA.resolve("kernel.img"));
        byte[] initramfs = Files.readAllBytes(TESTDATA.resolve("initramfs.cpio.gz"));
        byte[] dtb = Files.readAllBytes(TESTDATA.resolve("bcm2708-rpi-b.dtb"));
        return Bcm2835Machine.create(kernel, initramfs, dtb, CMDLINE, console, backend);
    }

    /// Digita `text` no UART0 no ritmo de um byte por bloco de fatias — o FIFO de recepção do
    /// PL011 tem 16 posições e descarta o excedente como hardware real (armadilha registrada na
    /// B4.1.5, reaproveitada aqui pois o `Pl011Uart` é o mesmo, sem modificação).
    private static void type(Bcm2835Machine machine, String text) {
        for (byte typed : text.getBytes(StandardCharsets.US_ASCII)) {
            machine.typeByte(typed & 0xFF);
            for (int slice = 0; slice < SLICES_PER_TYPED_BYTE; slice++) {
                machine.runSlice();
            }
        }
    }

    private static boolean runUntil(Bcm2835Machine machine, ByteArrayOutputStream console, String marker) {
        return runUntilMoreThan(machine, console, marker, occurrences(text(console), marker));
    }

    private static boolean runUntilMoreThan(Bcm2835Machine machine, ByteArrayOutputStream console,
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
