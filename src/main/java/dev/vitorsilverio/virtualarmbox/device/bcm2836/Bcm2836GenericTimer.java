package dev.vitorsilverio.virtualarmbox.device.bcm2836;

import dev.vitorsilverio.armjitter.core64.Aarch64SystemRegisterBus;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;

/// Timer genérico ARM (`arm,armv7-timer` no `.dtb` — mesmo binding usado em AArch32 e AArch64,
/// task F11), comparador **físico não-seguro** (`CNTP_*`) apenas — o único que
/// `drivers/clocksource/arm_arch_timer.c` arma por padrão num boot EL1 sem hipervisor
/// (`CNTV_*`/virtual fica fora, sem consumidor real: só teria efeito sob EL2, que este
/// emulador não modela, task B6.6.7 "Não inclui"). Diferente de {@code Bcm2835SystemTimer}
/// (MMIO): este NÃO é um periférico endereçado por memória — é acessado pelo guest via
/// `MRS`/`MSR` de registrador de sistema A64, então implementa
/// {@link Aarch64SystemRegisterBus} (instalado em {@code Aarch64Core#setSystemRegisterBus},
/// composto com {@link dev.vitorsilverio.virtualarmbox.boot.CompositeSystemRegisterBus} junto
/// com `Aarch64VmsaSystemRegisters`, já que os dois cobrem subconjuntos DISJUNTOS de
/// {@link Aarch64SystemRegisterId} — nenhum dos dois pode ser o único bus instalado).
///
/// Modelo de tempo: mesma disciplina de {@code Bcm2835SystemTimer}/`Sp804DualTimer` (sem
/// relógio de parede real) — o contador tica 1:1 com os ciclos de CPU emulados consumidos
/// entre fatias ({@link #advance}), e {@link #CNTFRQ_HZ} é só o valor DECLARADO ao guest (o
/// cristal real de 19,2MHz do Raspberry Pi 3), usado por ele para converter ticks em
/// microssegundos — não precisa bater com nenhum relógio de parede real do host.
public final class Bcm2836GenericTimer implements Aarch64SystemRegisterBus {
    /// Frequência declarada ao guest via `CNTFRQ_EL0` — valor real do cristal do Raspberry Pi 3
    /// (`bcm2710-rpi-3-b.dtb`/documentação de hardware), não calibrado contra tempo real.
    private static final long CNTFRQ_HZ = 19_200_000L;
    private static final long CTL_ENABLE = 1L;
    private static final long CTL_IMASK = 1L << 1;
    private static final long CTL_ISTATUS = 1L << 2;
    /// Máscara de 32 bits usada por `CNTP_TVAL_EL0` (`ARM DDI 0487 D11.2.4`: campo `TimerValue`
    /// é um `int32_t`, sinal estendido ao ler/escrever).
    private static final long TVAL_32BIT_MASK = 0xFFFF_FFFFL;

    private long counter;
    private long compareValue;
    private boolean enabled;
    private boolean masked;

    /// Avança o contador livre por `deltaCycles` ciclos de CPU emulados (1:1 — ver Javadoc da
    /// classe) e reavalia `ISTATUS`. Chamado pelo hospedeiro a cada fatia, mesmo padrão de
    /// `Bcm2835SystemTimer#advance`.
    public void advance(long deltaCycles) {
        counter += deltaCycles;
    }

    /// `true` quando o comparador expirou E o guest não mascarou (`ENABLE=1,IMASK=0,ISTATUS=1`)
    /// — a condição real que o BCM2836 roteia como PPI `nCNTPNSIRQ` para
    /// {@link Bcm2836LocalIntc}. `ISTATUS` em si (setado independente de `IMASK`, `ARM DDI 0487`
    /// pseudocódigo de `CNTP_CTL_EL0`) fica disponível separadamente via
    /// {@link #istatusSet()} caso um consumidor precise distinguir "expirou" de "expirou E
    /// entregue".
    public boolean physicalTimerIrqPending() {
        return enabled && !masked && istatusSet();
    }

    private boolean istatusSet() {
        return counter >= compareValue;
    }

    @Override
    public boolean handles(Aarch64SystemRegisterId register) {
        return switch (register) {
            case CNTFRQ_EL0, CNTPCT_EL0, CNTP_TVAL_EL0, CNTP_CTL_EL0, CNTP_CVAL_EL0 -> true;
            default -> false;
        };
    }

    @Override
    public long read(Aarch64SystemRegisterId register) {
        return switch (register) {
            case CNTFRQ_EL0 -> CNTFRQ_HZ;
            case CNTPCT_EL0 -> counter;
            case CNTP_TVAL_EL0 -> (compareValue - counter) & TVAL_32BIT_MASK;
            case CNTP_CTL_EL0 -> (enabled ? CTL_ENABLE : 0)
                    | (masked ? CTL_IMASK : 0)
                    | (istatusSet() ? CTL_ISTATUS : 0);
            case CNTP_CVAL_EL0 -> compareValue;
            default -> throw new UnsupportedOperationException(
                    "Bcm2836GenericTimer não atende: " + register);
        };
    }

    @Override
    public void write(Aarch64SystemRegisterId register, long value) {
        switch (register) {
            case CNTFRQ_EL0 -> { /* somente-leitura para este emulador — valor fixo do hardware real. */ }
            case CNTPCT_EL0 -> { /* somente-leitura pelo guest em EL1 (hardware real também recusa). */ }
            case CNTP_TVAL_EL0 -> compareValue = counter + signExtend32(value);
            case CNTP_CTL_EL0 -> {
                enabled = (value & CTL_ENABLE) != 0;
                masked = (value & CTL_IMASK) != 0;
                // ISTATUS é somente-leitura (calculado), escrita nesse bit é ignorada.
            }
            case CNTP_CVAL_EL0 -> compareValue = value;
            default -> throw new UnsupportedOperationException(
                    "Bcm2836GenericTimer não atende: " + register);
        }
    }

    private static long signExtend32(long value) {
        return (long) (int) value;
    }
}
