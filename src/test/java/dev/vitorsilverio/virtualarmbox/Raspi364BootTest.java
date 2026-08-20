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
/// **Sessão 2026-08-20 (retomada após B6.8) — SEGUNDO bloqueio real, ainda NÃO fechado**: com
/// `CCMP`/`CCMN` disponíveis, o boot avança de fato (a primeira instrução já não lança) até o
/// endereço `0x13ba9e8`, onde encontra `0xaa0003f5` — `ORR X21, XZR, X0` (`LSL #0`), ou seja, o
/// alias `MOV X21, X0` da classe **"Logical (shifted register)"**. Essa classe (que cobre
/// `AND`/`ORR`/`EOR`/`ANDS`/`BIC`/`ORN`/`EON`/`BICS` com operando registrador — INCLUINDO o alias
/// `MOV` de registrador, uma das instruções A64 mais comuns que existem) está, segundo o
/// comentário explícito em `Aarch64Decoder#decodeDataProcessingRegister`
/// (`arm-jitter/core/.../decoder64/Aarch64Decoder.java`, linha ~942): "`Logical (shifted
/// register)`: fora do escopo fechado do épico (ver a task B6.3.1)" — ou seja, um gap de feature
/// CONHECIDO e documentado desde B6.3.1, nunca coberto por nenhuma sub-task de B6 até agora
/// (B6.3.1-B6.3.4 cobriram ALU shifted/extended register, `CSEL`/aliases, bitfield, mul/div,
/// exclusive access; B6.8 cobriu só `CCMP`/`CCMN`). **Decisão desta sessão**: mesma categoria da
/// sessão anterior — não é um bug (a lib nunca prometeu essa classe), é uma lacuna de feature
/// fora do "Inclui"/"Não inclui" desta task ("Sem mudança no `arm-jitter`. Exceção: bug real da
/// lib") — precisa de uma sub-task própria no `arm-jitter` (mesmo rigor de corpus real do resto
/// do épico B6.3/B6.8), decisão para o usuário priorizar. Dado que `MOV` de registrador é
/// onipresente em qualquer prólogo/epílogo de função A64 real, é provável que este seja o
/// PRÓXIMO obstáculo dominante para qualquer kernel real (não uma curiosidade isolada como
/// `CCMP` no polyglot EFI). {@link #reachesEarlyconBannerInterpreted}/
/// {@link #reachesEarlyconBannerJit}/{@link #reachesFreeingKernelMemoryInterpreted} continuam
/// `@Disabled` até essa nova sub-task fechar.
class Raspi364BootTest {
    private static final Path TESTDATA = Path.of("testdata", "raspi3-64");
    private static final String CMDLINE = "console=ttyAMA0,115200 earlycon root=/dev/ram rdinit=/init";
    private static final String EARLYCON_BANNER = "Booting Linux on physical CPU";
    private static final String FREEING_KERNEL_MEMORY = "Freeing unused kernel";
    /// Segundo bloqueio real (sessão 2026-08-20, após `CCMP`/`CCMN` fecharem em B6.8): "Logical
    /// (shifted register)" (`AND`/`ORR`/`EOR`/... com operando registrador, incl. o alias `MOV`
    /// de registrador) é gap de feature documentado desde B6.3.1, nunca implementado. Ver
    /// Javadoc da classe.
    private static final String LOGICAL_SHIFTED_REGISTER_NOT_IMPLEMENTED_MESSAGE =
            "AArch64: encoding fora da fatia B6.1 em 0x13ba9e8: 0xaa0003f5";

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

        // Achado desta sessão (2026-08-20, ver Javadoc da classe): CCMP/CCMN (B6.8) desbloquearam
        // a primeira instrução, mas o boot bate agora em "Logical (shifted register)"
        // (ORR/alias MOV de registrador), um SEGUNDO gap de feature real, também fora do escopo
        // desta task. PINADO aqui como regressão: se uma sub-task futura implementar essa classe,
        // este teste PRECISA ser atualizado (não é mais "não implementado"), sinal correto de que
        // o bloqueio abriu — e é provável que revele um TERCEIRO gap mais à frente.
        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                machine::runSlice, "esperava o gap de decode conhecido (Logical shifted register) "
                        + "— se isto não lançar mais, o bloqueio da task F11 abriu, atualizar este teste");
        assertEquals(LOGICAL_SHIFTED_REGISTER_NOT_IMPLEMENTED_MESSAGE, thrown.getMessage());
    }

    @Disabled("F11 (2026-08-20): CCMP/CCMN (B6.8) fecharam o primeiro bloqueio, mas o boot agora "
            + "bate num SEGUNDO gap de decode real do arm-jitter: 'Logical (shifted register)' "
            + "(AND/ORR/EOR/... incl. o alias MOV de registrador), documentado como fora de escopo "
            + "desde B6.3.1. Ver Javadoc da classe para o achado completo. Fora do escopo desta "
            + "task (não é bug, é feature ausente) — precisa de sub-task própria no arm-jitter.")
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void reachesEarlyconBannerInterpreted() throws Exception {
        assertReachesMarker(Raspi364Machine.Backend.INTERPRETED, EARLYCON_BANNER);
    }

    @Disabled("F11 (2026-08-20): mesmo bloqueio de reachesEarlyconBannerInterpreted (Logical "
            + "shifted register, ver Javadoc da classe).")
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void reachesEarlyconBannerJit() throws Exception {
        assertReachesMarker(Raspi364Machine.Backend.JIT, EARLYCON_BANNER);
    }

    @Disabled("F11 (2026-08-20): mesmo bloqueio de reachesEarlyconBannerInterpreted (Logical "
            + "shifted register, ver Javadoc da classe).")
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
