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
/// **Sessão 2026-08-18 (primeira tentativa de boot real) — BLOQUEIO REAL achado, NÃO fechado
/// nesta sessão**: a infra em si (RAM/MMU/DTB/registradores/handoff) foi provada correta —
/// {@link #smokeTestBootsWithoutException} confirma `PC=0`/`X0=`endereço do DTB/`inEl1=true`
/// batendo com o que o próprio `kernel8.img` real espera, e a PRIMEIRA instrução do kernel8.img
/// real (`ccmp x18, #0x0, #0xd, pl`, offset `0x0` — CONFIRMADO via
/// `aarch64-none-elf-objdump -D -b binary -m aarch64` direto sobre o binário real, não suposição)
/// bate byte a byte com o que `Aarch64Decoder` recebe (`0xfa405a4d` no endereço `0x0`) — ou
/// seja, o pipeline de carga está 100% correto. **O bloqueio é um gap de decode real do
/// `arm-jitter`**: `CCMP`/`CCMN` (Conditional Compare, imediato/registrador — classe "Data
/// Processing (Register)") NUNCA foram implementados em nenhuma sub-task do épico B6.3
/// (B6.3.1-B6.3.4 cobriram ALU shifted/extended register, `CSEL`/aliases, bitfield, mul/div,
/// exclusive access — `CCMP`/`CCMN` não estavam na lista). A ARM engenharia deliberadamente o
/// `code0` de um `Image` com EFI stub para começar com os bytes `"MZ"` (assinatura DOS/PE, para o
/// binário também ser um executável EFI válido) — `ccmp x18, #0x0, #0xd, pl` foi a instrução real
/// escolhida pelos mantenedores do kernel para satisfazer as duas restrições ao mesmo tempo
/// (`arch/arm64/kernel/head.S`/`Documentation/arch/arm64/booting.rst` — não é um acidente deste
/// `kernel8.img` específico, é o mecanismo do EFI stub para TODO kernel arm64 com essa opção
/// habilitada, então praticamente qualquer kernel real distribuído vai bater nesta MESMA
/// instrução como primeira). **Decisão desta sessão**: implementar `CCMP`/`CCMN` no `arm-jitter`
/// está FORA do "Inclui"/"Não inclui" desta task (`f11-raspi3-aarch64-machine.md`: "Sem mudança
/// no `arm-jitter`. Exceção: bug real da lib" — isto não é um bug, é uma lacuna de feature
/// legítima, categoria diferente das correções inline que a F3 fez para bugs REAIS de
/// comportamento incorreto do CP15/DFSR) — precisa de uma sub-task própria no `arm-jitter`
/// (mesmo rigor de corpus real do resto do épico B6.3, decisão para o usuário priorizar).
/// {@link #reachesEarlyconBannerInterpreted}/{@link #reachesEarlyconBannerJit}/
/// {@link #reachesFreeingKernelMemoryInterpreted} ficam `@Disabled` até essa sub-task fechar.
class Raspi364BootTest {
    private static final Path TESTDATA = Path.of("testdata", "raspi3-64");
    private static final String CMDLINE = "console=ttyAMA0,115200 earlycon root=/dev/ram rdinit=/init";
    private static final String EARLYCON_BANNER = "Booting Linux on physical CPU";
    private static final String FREEING_KERNEL_MEMORY = "Freeing unused kernel";
    private static final String CCMP_NOT_IMPLEMENTED_MESSAGE =
            "AArch64: encoding fora da fatia B6.1 em 0x0: 0xfa405a4d";

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

        // Achado desta sessão (ver Javadoc da classe): a PRIMEIRA instrução real do kernel8.img
        // (CCMP, parte do truque de polyglot EFI/"MZ") não é decodificada pelo arm-jitter hoje —
        // gap de feature real, fora do escopo desta task. PINADO aqui como regressão: se uma
        // sub-task futura implementar CCMP/CCMN, este teste PRECISA ser atualizado (não é mais
        // "não implementado"), o que é o sinal correto de que o bloqueio abriu.
        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                machine::runSlice, "esperava o gap de decode conhecido (CCMP) — se isto não lançar "
                        + "mais, o bloqueio da task F11 abriu, atualizar este teste");
        assertEquals(CCMP_NOT_IMPLEMENTED_MESSAGE, thrown.getMessage());
    }

    @Disabled("F11 (2026-08-18): bloqueado no gap de decode real CCMP/CCMN do arm-jitter (Data "
            + "Processing Register) — a PRIMEIRA instrução de qualquer kernel8.img real com EFI "
            + "stub (truque polyglot MZ) usa CCMP. Ver Javadoc da classe para o achado completo. "
            + "Fora do escopo desta task (não é bug, é feature ausente) — precisa de sub-task "
            + "própria no arm-jitter.")
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void reachesEarlyconBannerInterpreted() throws Exception {
        assertReachesMarker(Raspi364Machine.Backend.INTERPRETED, EARLYCON_BANNER);
    }

    @Disabled("F11 (2026-08-18): mesmo bloqueio de reachesEarlyconBannerInterpreted (CCMP/CCMN).")
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void reachesEarlyconBannerJit() throws Exception {
        assertReachesMarker(Raspi364Machine.Backend.JIT, EARLYCON_BANNER);
    }

    @Disabled("F11 (2026-08-18): mesmo bloqueio de reachesEarlyconBannerInterpreted (CCMP/CCMN).")
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
