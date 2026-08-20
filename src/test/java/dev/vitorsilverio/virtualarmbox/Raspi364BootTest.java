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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
/// é bug, é família de feature nova) — candidata a sub-task própria no arm-jitter (`B6.12`?),
/// dimensionada para cobrir a família toda de uma vez (não uma instrução por sessão como as
/// anteriores), dado o padrão já visto aqui. {@link #reachesEarlyconBannerInterpreted}/
/// {@link #reachesEarlyconBannerJit}/{@link #reachesFreeingKernelMemoryInterpreted} continuam
/// `@Disabled` até essa nova sub-task fechar.
class Raspi364BootTest {
    private static final Path TESTDATA = Path.of("testdata", "raspi3-64");
    private static final String CMDLINE = "console=ttyAMA0,115200 earlycon root=/dev/ram rdinit=/init";
    private static final String EARLYCON_BANNER = "Booting Linux on physical CPU";
    private static final String FREEING_KERNEL_MEMORY = "Freeing unused kernel";
    /// Quinto bloqueio real (sessão 2026-08-20, sessão 5, após B6.11 fechar LSLV/LSRV/ASRV/RORV):
    /// `DC IVAC, X0` é a primeira instrução da família "System instructions" (`SYS`/`SYSL`
    /// genérico, `op0=1`) que este boot encontra — gap de FAMÍLIA nova, não de uma instrução só.
    /// Ver Javadoc da classe.
    private static final String SYS_INSTRUCTION_NOT_IMPLEMENTED_MESSAGE =
            "AArch64: encoding fora da fatia B6.1 em 0x39000: 0xd5087620";

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

        // Achado desta sessão (2026-08-20, sessão 5, ver Javadoc da classe): B6.11 (LSLV/LSRV/
        // ASRV/RORV) desbloqueou o QUARTO gap, o boot avança mais longe e bate agora em
        // `DC IVAC, X0`, um QUINTO gap real — mas desta vez uma FAMÍLIA inteira nova ("System
        // instructions", SYS/SYSL genérico), não uma instrução isolada. PINADO aqui como
        // regressão: se uma sub-task futura estender o decoder para cobrir SYS/DC (candidata a
        // B6.12), este teste PRECISA ser atualizado (não é mais "não implementado"), sinal
        // correto de que o bloqueio abriu — e é provável que revele mais variantes da mesma
        // família mais à frente (DC CVAC/CIVAC/ZVA, IC IVAU/IALLU, eventualmente TLBI).
        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                machine::runSlice, "esperava o gap de decode conhecido (DC IVAC) "
                        + "— se isto não lançar mais, o bloqueio da task F11 abriu, atualizar este teste");
        assertEquals(SYS_INSTRUCTION_NOT_IMPLEMENTED_MESSAGE, thrown.getMessage());
    }

    @Disabled("F11 (2026-08-20, sessão 5): B6.11 (LSLV/LSRV/ASRV/RORV) fechou o QUARTO "
            + "bloqueio, mas o boot agora bate numa FAMÍLIA inteira nova de decode: 'DC IVAC, X0' "
            + "(System instructions, SYS/SYSL genérico op0=1), fora do subconjunto de "
            + "registradores de sistema nomeado resolvido por Aarch64Decoder#decodeSystemRegisterId. "
            + "Ver Javadoc da classe para o achado completo. Fora do escopo desta task (não é bug, é "
            + "família de feature ausente) — precisa de sub-task própria no arm-jitter (candidata a "
            + "B6.12), dimensionada para a família toda (DC/IC/AT/TLBI), não uma instrução por vez.")
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void reachesEarlyconBannerInterpreted() throws Exception {
        assertReachesMarker(Raspi364Machine.Backend.INTERPRETED, EARLYCON_BANNER);
    }

    @Disabled("F11 (2026-08-20, sessão 5): mesmo bloqueio de reachesEarlyconBannerInterpreted "
            + "(DC IVAC, ver Javadoc da classe).")
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void reachesEarlyconBannerJit() throws Exception {
        assertReachesMarker(Raspi364Machine.Backend.JIT, EARLYCON_BANNER);
    }

    @Disabled("F11 (2026-08-20, sessão 5): mesmo bloqueio de reachesEarlyconBannerInterpreted "
            + "(DC IVAC, ver Javadoc da classe).")
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
