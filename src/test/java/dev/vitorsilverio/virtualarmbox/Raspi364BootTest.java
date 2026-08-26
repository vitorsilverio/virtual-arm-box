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

/// Boot do Raspberry Pi 3 real (`kernel8.img`, AArch64) sobre {@link Raspi364Machine} — task
/// F11. Mesmo formato de {@code Raspi1BootTest} (F3): um `smokeTest` barato (só prova que a
/// infra de carga/handoff está correta) e `assertReachesMarker` que roda até um texto aparecer
/// no console ou o orçamento de fatias esgotar.
///
/// **Sessão 2026-08-18 (primeira tentativa de boot real)**: achou o gap `CCMP`/`CCMN`
/// (Conditional Compare) na PRIMEIRA instrução do `kernel8.img` (`ccmp x18, #0x0, #0xd, pl`,
/// offset `0x0`, truque de polyglot EFI "MZ" — ver `arch/arm64/kernel/head.S`). Documentado como
/// gap de feature (não bug) e devolvido ao usuário; fechado por uma sub-task separada no
/// `arm-jitter` (`B6.8`, 2026-08-20, `CCMP`/`CCMN` decodificados e executados no interpretador).
///
/// **Sessão 2026-08-20 (retomada após B6.8) — segundo bloqueio real, FECHADO por B6.9**: com
/// `CCMP`/`CCMN` disponíveis, o boot avançou até `0x13ba9e8` (`0xaa0003f5`, `ORR X21, XZR, X0` —
/// alias `MOV` da classe "Logical (shifted register)"), gap fechado pela sub-task `B6.9` do
/// arm-jitter (`AND`/`ORR`/`EOR`/`ANDS`/`BIC`/`ORN`/`EON`/`BICS` + aliases `MOV`/`MVN`).
///
/// **Sessão 2026-08-20 (retomada após B6.9, sessão 4) — TERCEIRO bloqueio real, FECHADO por
/// B6.10**: com "Logical (shifted register)" disponível, o boot avança bem mais longe (de
/// `0x13ba9e8` para `0x38fc4` — dezenas de milhares de instruções a mais) até
/// `0xd53b0023` = `MRS X3, CTR_EL0` (Cache Type Register), gap fechado pela sub-task `B6.10` do
/// arm-jitter (`CTR_EL0`/`DCZID_EL0` no mesmo grupo `op0=3,op1=3,CRn=0` do timer genérico).
///
/// **Sessão 2026-08-20 (retomada após B6.10, sessão 5) — QUARTO bloqueio real, FECHADO por
/// B6.11**: com `CTR_EL0` disponível, o boot avança de `0x38fc4` para `0x38fd4` até
/// `0x9ac32042` = `LSL X2, X2, X3` (alias de `LSLV`, "Data-processing (2 source)", quantidade de
/// deslocamento tomada de `Rm` EM TEMPO DE EXECUÇÃO, não um imediato do encoding — família
/// `LSLV`/`LSRV`/`ASRV`/`RORV`, usada por `dcache_line_size()`/`icache_line_size()` para converter
/// o campo `CTR_EL0.{I,D}minLine` num tamanho de linha em bytes). Confirmado via
/// `aarch64-none-elf-as`/`objdump` (G1, oráculo real) ANTES de codificar. Gap fechado pela
/// sub-task `B6.11` do arm-jitter (`Ir64Op.ShiftVariable`, reaproveita `Ir64LogicalShiftType` de
/// B6.9).
///
/// **Sessão 2026-08-20 (retomada após B6.11, sessão 5, MESMA sessão) — QUINTO bloqueio real,
/// ainda NÃO fechado**: com `LSLV`/`LSRV`/`ASRV`/`RORV` disponíveis, o boot avança de `0x38fd4`
/// para `0x39000` até `0xd5087620`, confirmado via `aarch64-none-elf-as`/`objdump` (G1) como
/// `DC IVAC, X0` (Data Cache line Invalidate by VA to PoC) — instrução da classe **"System
/// instructions"** (`ARM DDI 0487 C5.2.9`/`C6.2.`, `op0=1` no encoding de `SYS`/`SYSL` genérico,
/// campos `op1`/`CRn`/`CRm`/`op2` selecionando a operação real: aqui `op1=0,CRn=7,CRm=6,op2=1`).
/// **Diferente das lacunas anteriores** (uma instrução isolada): esta é uma FAMÍLIA inteira nova
/// no decoder (`Aarch64Decoder` hoje só resolve `MRS`/`MSR` de registrador nomeado via
/// `decodeSystemRegisterId`, nunca viu `op0=1` — `DC`/`IC`/`AT`/`TLBI` são todos variantes do
/// MESMO encoding genérico `SYS`, distinguidos só por `CRn`/`CRm`/`op1`/`op2`), e `DC IVAC` é só
/// a PRIMEIRA que este boot bate — cache maintenance é onipresente em `head.S`/`cache.S`
/// (esperado: pelo menos `DC CVAC`/`DC CIVAC`/`DC ZVA`/`IC IVAU`/`IC IALLU` também apareçam mais à
/// frente, antes de alcançar TLBI, que exige EL1 e semântica de TLB que este projeto talvez nem
/// precise modelar de verdade — MMU/TLB do `arm-jitter` já são "sempre coerentes" por construção,
/// então `DC`/`IC`/`TLBI` provavelmente podem virar NO-OP aceito, não uma reimplementação de
/// cache real; CONFERIR com o usuário/task antes de presumir). **Fora do escopo desta task** (não
/// é bug, é família de feature nova) — gap fechado pela sub-task `B6.12` do arm-jitter (as 10
/// operações reais de `IC`/`DC` viram `NOP`, confirmado contra `helper.c` do QEMU; `DC ZVA`
/// deliberadamente excluída).
///
/// **Sessão 2026-08-20 (retomada após B6.12, sessão 6) — SEXTO achado real, um BUG (não gap de
/// feature), FIXADO nesta sessão**: com `DC IVAC` virando NO-OP, o boot avança de `0x39000` até
/// bem mais longe (1.456.350 fatias, ~157s) e então lança `ArithmeticException: integer overflow`
/// dentro de `AddressSpace64.Wrapping.read32` (`Math.toIntExact`). Causa raiz: `Wrapping`
/// (adapter que o `Raspi364Machine` usa para reaproveitar o barramento físico de 32 bits existente
/// via {@code AddressSpace64.wrapping}) usava `Math.toIntExact(address)`, que rejeita qualquer
/// `long` positivo maior que {@code Integer.MAX_VALUE} — inclusive endereços no intervalo
/// `[0x8000_0000, 0xFFFF_FFFF]`, que CABEM nos "4 GiB baixos" que o próprio Javadoc da classe
/// promete suportar (só interpretados como `int` NEGATIVO, não fora de faixa). Confirmado com um
/// harness de debug isolado (`DebugTtbr1`, fora do repo, descartado) instrumentando
/// `Wrapping`/`TranslatingAddressSpace64` temporariamente: o endereço que disparava a exceção
/// (ex.: PC `0x13b8164`, valores de registrador como `0xc0000000000f83`) SEMPRE caía dentro de
/// `[0x8000_0000, 0xFFFF_FFFF]`, nunca acima de `0xFFFF_FFFF` — provando que era exatamente essa
/// lacuna do `Math.toIntExact`, não um endereço realmente fora de faixa. **Corrigido** em
/// `AddressSpace64.java` (`arm-jitter`): `Wrapping` agora trunca com `(int)` (preserva o padrão de
/// bits, mesma convenção "endereço = int sem sinal" do resto de `AddressSpace`) e só lança
/// `ArithmeticException` de verdade para `< 0` ou `> 0xFFFF_FFFF`. Teste de regressão novo,
/// `AddressSpace64WrappingTest` (arm-jitter `core`). Commit separado no `arm-jitter`, `mvn -o
/// install` local refletido no `.m2`.
///
/// **Sessão 2026-08-20 (sessão 6, MESMA sessão, após o fix acima) — SÉTIMO bloqueio real, ainda
/// NÃO isolado por completo**: com o bug do `Wrapping` corrigido, o boot avança MAIS ainda (mesma
/// ordem de grandeza de fatias) até um `write64` (via `executeLoadStorePair`, `STP`) tentar
/// traduzir para um endereço físico genuinamente ACIMA de 4 GiB (`0x1_0000_0882`) — desta vez uma
/// rejeição correta de `Wrapping` (o PA está mesmo fora da faixa suportada, não é mais o bug do
/// `toIntExact`). Este é o mapa físico de RAM desta máquina (`RAM_SIZE_BYTES` ≈ `0x3F00_0000`,
/// bem abaixo de 4 GiB) recebendo um PA absurdo de um page-walk — **hipótese forte, NÃO
/// confirmada com instrumentação nesta sessão** (orçamento de tool-calls da "Disciplina de custo"
/// já consumido pela investigação do SEXTO achado): `TranslatingAddressSpace64` só implementa
/// `TTBR0_EL1` (achado documentado desde a spec de `B6.6.3`, comentário "só TTBR0 liga de
/// verdade" no precedente 32-bit) — Linux real usa `TTBR1_EL1` para o espaço de endereço do
/// kernel (VA alto, bit 63 setado) e este emulador, sem TTBR1, faz o walk usando a tabela ERRADA
/// (`ttbr0Base`) para qualquer VA de kernel, produzindo descritores de página lixo (não
/// inicializados/de outro propósito) cujos bits de PA combinados batem acima de 4 GiB por
/// coincidência. **Fora do escopo desta task** (feature ausente, não bug pontual) — candidata a
/// sub-task nova no arm-jitter (`B6.13`?): suporte a `TTBR1_EL1` em `TranslatingAddressSpace64`
/// (segunda tabela-base + lógica de seleção por VA alto/baixo, provavelmente via `TCR_EL1.T1SZ`)
/// + na ponte de registrador de sistema (`Aarch64VmsaSystemRegisters`, `B6.6.3`). Confirmar a
/// hipótese com instrumentação ANTES de implementar (mesma disciplina de todas as sub-tasks
/// anteriores desta fila) — pode haver outra causa.
///
/// **Sessão 2026-08-24 (retomada após B6.9/B6.13/B6.14 fecharem, todos ✅ desde então) — QUATRO
/// bloqueios reais novos achados e fechados na mesma sessão**: (1) `CPACR_EL1`
/// (`op0=3,op1=0,CRn=1,CRm=0,op2=2`, escrito logo após `SCTLR_EL1` em `head.S`) — nunca
/// decodificado; (2) o grupo inteiro de identidade `ID_AA64*` restante (`CRn=0,CRm=4-7`:
/// `ID_AA64PFR1_EL1`/`ID_AA64ZFR0_EL1`/`ID_AA64DFR1_EL1`/`ID_AA64ISAR1_EL1`/`ID_AA64ISAR2_EL1`/
/// `ID_AA64MMFR1_EL1`-`ID_AA64MMFR4_EL1`/`REVIDR_EL1`) — sondado em sequência por
/// `head.S`/`cpufeature.c`; resolvido de uma vez (não gap-a-gap) para não pagar um boot inteiro
/// por registrador; (3) **`TTBR1_EL1` de verdade** (não só decodificado — a hipótese refutada de
/// `B6.13` estava certa sobre a MECÂNICA, só errada sobre a causa do sétimo bloqueio antigo:
/// `TranslatingAddressSpace64` ganhou `setTtbr1`/segunda tabela-base, `walk()` seleciona por
/// `VA[55]` — bit 55, não bit 63, mesmo fato já registrado por B6.13 contra `aa64_va_parameters`
/// real do QEMU). Todos os 3 conferidos via `aarch64-none-elf-as`/`objdump` reais (devkitA64
/// disponível nesta sessão) antes de codificar — G1.
///
/// Com os 3 fechados, **o backend INTERPRETED roda 10 minutos inteiros sem lançar exceção
/// nenhuma** (nem decode gap, nem fault de tradução) — não há mais nenhum bloqueio "duro"
/// conhecido, mas também não alcança `EARLYCON_BANNER` dentro do orçamento (`MAX_SLICES` nem é
/// esgotado: o `@Timeout` de 10 minutos interrompe o laço no meio). Ou o boot é genuinamente mais
/// lento agora (mais registradores de sistema resolvidos → mais page-walks reais via `TTBR1_EL1`
/// sem essa via passar pela micro-TLB tão bem quanto `TTBR0`?) ou há um laço ocioso real (mesmo
/// padrão já visto na investigação de silêncio da F3, 2026-08-16) — **não isolado nesta sessão**
/// (disciplina de custo: 10-15min de orçamento para INTERPRETED, já consumido). O backend **JIT**
/// foi tentado como alternativa mais barata e diverge muito mais cedo, com uma falha DIFERENTE
/// (`MemoryTranslationException64: TRANSLATION_FAULT_L3 em 0x200`, ~1s) — não teve nenhuma
/// investigação nesta sessão (pode ser um bug real do caminho JIT nunca exercitado por um boot A64
/// tão profundo, ou uma diferença de estado entre os dois backends nesta máquina). **Candidata a
/// sessão dedicada**: (a) instrumentar por que o INTERPRETED não avança mais rápido/não alcança o
/// marco (profiling de onde o tempo vai — quantos slices/s, se há um `WFI`/loop-e-volta), (b)
/// investigar a divergência do JIT separadamente (endereço `0x200` bem baixo sugere um problema
/// diferente, talvez anterior aos 3 gaps fechados aqui). {@link #reachesEarlyconBannerInterpreted}/
/// {@link #reachesEarlyconBannerJit}/{@link #reachesFreeingKernelMemoryInterpreted} continuam
/// `@Disabled` até uma dessas frentes fechar.
///
/// **Sessão 8 (2026-08-26) — frente (b) da sessão 7 fechada: causa raiz REAL da divergência JIT
/// achada e corrigida no `arm-jitter` (2 bugs, não 1)**. `TRANSLATION_FAULT_L3 em 0x200
/// (DATA_READ)` não vinha de um bloco JÁ compilado executando errado — vinha do PRÓPRIO `lift()`
/// tentando decidir onde um bloco QUENTE termina: `StandardIr64BlockLifter` decodifica instruções
/// À FRENTE do PC atual (até `maxBlockInstructions`, sem nenhum desvio ainda visto) só para
/// delimitar o bloco, e esse lookahead cruzou para uma página de código sem descritor de página
/// válido — memória que a execução real talvez NUNCA alcance (um desvio pode ocorrer antes).
/// `JitRuntime64#execute` nunca cercava esse `lift()` com um `try/catch` de
/// `MemoryTranslationException64` — o precedente 32-bit (`JitRuntime#LIFT_FAULT_CYCLES`) já tinha
/// essa proteção desde a task `B4.1.5`, nunca foi portado para o mundo A64. Corrigido espelhando o
/// mesmo padrão (`JitRuntime64#LIFT_FAULT_CYCLES`, novo). **Achado 2, encontrado ANTES deste
/// (mesma sessão) por uma hipótese diferente que acabou não sendo a causa deste sintoma
/// específico, mas era um bug real e mais grave**: `Ir64BlockCompiler` (o compilador de bytecode
/// nativo A64) nunca cercava o bloco compilado com NENHUM `try/catch` — ao contrário do precedente
/// 32-bit (`AsmBlockCompiler`, desde `B4.1.3`), QUALQUER falta de tradução (ou `BRK`/instrução
/// indefinida/`HVC`/`SMC`) no MEIO da execução de um bloco A64 já promovido a nativo escapava como
/// exceção do HOST em vez de entrar no handler do guest — bug latente em TODO bloco A64 quente,
/// não só neste boot. Corrigido com os mesmos 5 handlers que `Ir64BlockExecutor#executeBlock` já
/// tinha. Os dois fixes têm testes de regressão dedicados no `arm-jitter`
/// (`Ir64BlockCompilerMemoryAbortTest`, `JitRuntime64LiftFaultTest`) — confirmados FALHANDO sem o
/// fix e passando com ele, mesma disciplina de toda task desta fila. **Efeito medido nesta
/// sessão** (contra um `arm-jitter` local com os 2 fixes, ainda NÃO publicado — ver
/// {@link #reachesEarlyconBannerJit}): o backend JIT deixa de lançar QUALQUER exceção e roda os
/// 2.000.000 de fatias completos do orçamento do teste (114s) sem crashar — mas, como o
/// INTERPRETED, não alcança `EARLYCON_BANNER` dentro do orçamento. **F11 segue 🟡 PARCIAL**: a
/// frente (b) da sessão 7 fechou (causa raiz achada+corrigida), a frente (a) (lento vs. preso)
/// continua aberta para ambos os backends agora que convergem no mesmo sintoma.
class Raspi364BootTest {
    private static final Path TESTDATA = Path.of("testdata", "raspi3-64");
    private static final String CMDLINE = "console=ttyAMA0,115200 earlycon root=/dev/ram rdinit=/init";
    private static final String EARLYCON_BANNER = "Booting Linux on physical CPU";
    private static final String FREEING_KERNEL_MEMORY = "Freeing unused kernel";
    private static final int MAX_SLICES = 2_000_000;
    private static final int CONSOLE_POLL_INTERVAL = 200;

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void smokeTestBootsWithoutException() throws Exception {
        assumeTrue(Files.exists(TESTDATA.resolve("kernel8.img")), "assets reais ausentes nesta checkout");
        Raspi364Machine machine = load(Raspi364Machine.Backend.INTERPRETED, new ByteArrayOutputStream());

        // `text_offset` deste kernel8.img real (byte 8, u64 LE) é 0x0 — kernels modernos são
        // position-independent e frequentemente usam TEXT_OFFSET=0 (confirmado por inspeção de
        // bytes, não um erro de carga: `RAM_BASE(0) + text_offset(0) = 0`).
        assertEquals(0L, machine.core().pc(), "entrada esperada no endereço de link (text_offset=0)");
        assertTrue(machine.core().x(0) > 0, "X0 (endereço do DTB) deve ser não-nulo");
        assertTrue(machine.core().exceptionState().inEl1(), "esta máquina simula entrega em EL1 (D2)");

        // Sessão 2026-08-20 (sessão 6, após B6.12 fechar DC/IC como NOP): o gap `DC IVAC` não
        // lança mais — B6.12 tratou as 10 operações reais de manutenção de cache como NOP
        // (confirmado contra `helper.c` do QEMU). `runSlice()` agora avança sem lançar.
        machine.runSlice();
    }

    @Disabled("F11 (2026-08-24, sessão 7): sem bloqueio duro conhecido (10min de INTERPRETED "
            + "correm sem exceção), mas não alcança o marco no orçamento — causa (lentidão real "
            + "vs. laço ocioso) não isolada nesta sessão. Ver Javadoc da classe.")
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void reachesEarlyconBannerInterpreted() throws Exception {
        assertReachesMarker(Raspi364Machine.Backend.INTERPRETED, EARLYCON_BANNER);
    }

    @Disabled("F11 (sessão 8, 2026-08-26): a divergência JIT foi CAUSA-RAIZ ACHADA E CORRIGIDA no "
            + "arm-jitter (2 bugs reais, ver Javadoc da classe) — mas a correção ainda não foi "
            + "publicada no Maven Central (fica em 1.1.0), então esta versão continua reproduzindo "
            + "o crash antigo. Reabilitar depois que uma versão nova do arm-jitter for publicada "
            + "(F5) e este repo migrar para ela (F7) — nessa hora, medido localmente contra o "
            + "arm-jitter corrigido, o JIT já não lança mais nenhuma exceção e roda os 2.000.000 de "
            + "fatias completos (mesmo teto do INTERPRETED), mas AINDA NÃO alcança o marco — mesmo "
            + "bloqueio (lento vs. preso, não isolado) de reachesEarlyconBannerInterpreted.")
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void reachesEarlyconBannerJit() throws Exception {
        assertReachesMarker(Raspi364Machine.Backend.JIT, EARLYCON_BANNER);
    }

    @Disabled("F11 (2026-08-24, sessão 7): mesmo bloqueio de reachesEarlyconBannerInterpreted "
            + "(marco anterior a este nunca fechou nesta sessão). Ver Javadoc da classe.")
    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void reachesFreeingKernelMemoryInterpreted() throws Exception {
        assertReachesMarker(Raspi364Machine.Backend.INTERPRETED, FREEING_KERNEL_MEMORY);
    }

    private static void assertReachesMarker(Raspi364Machine.Backend backend, String marker) throws Exception {
        assumeTrue(Files.exists(TESTDATA.resolve("kernel8.img")), "assets reais ausentes nesta checkout");
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        Raspi364Machine machine = load(backend, console);

        boolean reached = runUntil(machine, console, marker);
        assertTrue(reached, "esperava '" + marker + "' no console, obtive:\n" + text(console));
    }

    private static Raspi364Machine load(Raspi364Machine.Backend backend, ByteArrayOutputStream console)
            throws Exception {
        byte[] kernel = Files.readAllBytes(TESTDATA.resolve("kernel8.img"));
        byte[] initramfs = Files.readAllBytes(TESTDATA.resolve("initramfs.cpio.gz"));
        byte[] dtb = Files.readAllBytes(TESTDATA.resolve("bcm2710-rpi-3-b.dtb"));
        return Raspi364Machine.create(kernel, initramfs, dtb, CMDLINE, console, backend);
    }

    private static boolean runUntil(Raspi364Machine machine, ByteArrayOutputStream console, String marker) {
        return runUntilMoreThan(machine, console, marker, occurrences(text(console), marker));
    }

    private static boolean runUntilMoreThan(Raspi364Machine machine, ByteArrayOutputStream console,
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
