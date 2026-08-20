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
/// **Sessão 2026-08-20 (retomada após B6.9, sessão 4) — TERCEIRO bloqueio real, ainda NÃO
/// fechado**: com "Logical (shifted register)" disponível, o boot avança bem mais longe (de
/// `0x13ba9e8` para `0x38fc4` — dezenas de milhares de instruções a mais) até encontrar
/// `0xd53b0023` em `0x38fc4`. Decodificado manualmente campo a campo (`Rt=X3` bits[4:0]=`00011`,
/// `op2=1` bits[7:5], `CRm=0` bits[11:8], `CRn=0` bits[15:12], `op1=3` bits[18:16], `o0=1`
/// bit[19] → `op0=3`, `L=1` → leitura): é `MRS X3, CTR_EL0` (Cache Type Register, EL0-acessível),
/// uma leitura ONIPRESENTE em qualquer boot A64 real (`dcache_line_size`/`icache_line_size` em
/// `arch/arm64/kernel/head.S`/`cache.S`). Confirmado no código real do arm-jitter, não só por
/// inspeção de bits: `Aarch64Decoder#decodeSystemRegisterId`
/// (`arm-jitter/core/.../decoder64/Aarch64Decoder.java`, linha ~1476) só resolve `op0=3` com
/// `op1=SYSREG_OP1_EL1`(registradores EL1 "gerais") OU `op1=SYSREG_OP1_EL0_TIMER`(=3) restrito a
/// `CRn=14` (timer genérico, B6.6.7) — `CTR_EL0` tem `op1=3` mas `CRn=0`
/// (`decodeGenericTimerRegisterId` devolve `null` porque `crn != SYSREG_CRN_TIMER`), então cai no
/// `unsupported()` genérico. **Decisão desta sessão**: mesma categoria das duas sessões
/// anteriores — não é bug (a lib nunca prometeu esse registrador), é lacuna de feature fora do
/// escopo desta task ("Sem mudança no `arm-jitter`. Exceção: bug real da lib") — precisa de nova
/// sub-task no arm-jitter (candidata a `B6.10`, mesmo rigor de corpus real de B6.8/B6.9) para
/// estender `decodeSystemRegisterId`/`Aarch64SystemRegisterId` com o grupo "AArch64 Identification
/// registers, EL0-acessível" (`op0=3,op1=3,CRn=0`) — `CTR_EL0` no mínimo, possivelmente
/// `DCZID_EL0` (`CRm=0,op2=7`, também comum em `head.S` para `ZVA`) já que é a mesma família de
/// registrador. {@link #reachesEarlyconBannerInterpreted}/{@link #reachesEarlyconBannerJit}/
/// {@link #reachesFreeingKernelMemoryInterpreted} continuam `@Disabled` até essa nova sub-task
/// fechar.
class Raspi364BootTest {
    private static final Path TESTDATA = Path.of("testdata", "raspi3-64");
    private static final String CMDLINE = "console=ttyAMA0,115200 earlycon root=/dev/ram rdinit=/init";
    private static final String EARLYCON_BANNER = "Booting Linux on physical CPU";
    private static final String FREEING_KERNEL_MEMORY = "Freeing unused kernel";
    /// Terceiro bloqueio real (sessão 2026-08-20, sessão 4, após "Logical shifted register"
    /// fechar em B6.9): `MRS X3, CTR_EL0` (Cache Type Register) é gap de feature real — o
    /// decoder só resolve `op0=3,op1=3` para o subconjunto do timer genérico (`CRn=14`, B6.6.7),
    /// não para o grupo de identificação EL0 (`CRn=0`) ao qual `CTR_EL0` pertence. Ver Javadoc
    /// da classe.
    private static final String CTR_EL0_NOT_IMPLEMENTED_MESSAGE =
            "AArch64: encoding fora da fatia B6.1 em 0x38fc4: 0xd53b0023";

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

        // Achado desta sessão (2026-08-20, sessão 4, ver Javadoc da classe): B6.9 (Logical
        // shifted register) desbloqueou o SEGUNDO gap, o boot avança bem mais longe e bate agora
        // em `MRS X3, CTR_EL0`, um TERCEIRO gap de feature real, também fora do escopo desta
        // task. PINADO aqui como regressão: se uma sub-task futura estender
        // `Aarch64SystemRegisterId` para cobrir CTR_EL0 (candidata a B6.10), este teste PRECISA
        // ser atualizado (não é mais "não implementado"), sinal correto de que o bloqueio abriu —
        // e é provável que revele um QUARTO gap mais à frente.
        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                machine::runSlice, "esperava o gap de decode conhecido (MRS CTR_EL0) "
                        + "— se isto não lançar mais, o bloqueio da task F11 abriu, atualizar este teste");
        assertEquals(CTR_EL0_NOT_IMPLEMENTED_MESSAGE, thrown.getMessage());
    }

    @Disabled("F11 (2026-08-20, sessão 4): B6.9 (Logical shifted register) fechou o SEGUNDO "
            + "bloqueio, mas o boot agora bate num TERCEIRO gap de decode real do arm-jitter: "
            + "'MRS X3, CTR_EL0' (Cache Type Register, EL0-acessível), fora do subconjunto de "
            + "registradores de sistema resolvido por Aarch64Decoder#decodeSystemRegisterId. Ver "
            + "Javadoc da classe para o achado completo. Fora do escopo desta task (não é bug, é "
            + "feature ausente) — precisa de sub-task própria no arm-jitter (candidata a B6.10).")
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void reachesEarlyconBannerInterpreted() throws Exception {
        assertReachesMarker(Raspi364Machine.Backend.INTERPRETED, EARLYCON_BANNER);
    }

    @Disabled("F11 (2026-08-20, sessão 4): mesmo bloqueio de reachesEarlyconBannerInterpreted "
            + "(MRS CTR_EL0, ver Javadoc da classe).")
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void reachesEarlyconBannerJit() throws Exception {
        assertReachesMarker(Raspi364Machine.Backend.JIT, EARLYCON_BANNER);
    }

    @Disabled("F11 (2026-08-20, sessão 4): mesmo bloqueio de reachesEarlyconBannerInterpreted "
            + "(MRS CTR_EL0, ver Javadoc da classe).")
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
