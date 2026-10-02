package dev.vitorsilverio.virtualarmbox.device.bcm2836;

import dev.vitorsilverio.armjitter.core64.Aarch64SystemRegisterBus;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;

/// Timer genérico ARM (`arm,armv7-timer` no `.dtb` — mesmo binding usado em AArch32 e AArch64,
/// task F11), comparadores **físico não-seguro** (`CNTP_*`) e **virtual** (`CNTV_*`) — o
/// virtual é o que `drivers/clocksource/arm_arch_timer.c` arma num boot EL1 sem hipervisor
/// (corrigido na sessão 9: a premissa anterior de que só o físico era usado estava errada).
/// Diferente de {@code Bcm2835SystemTimer}
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
    private final Comparator physical = new Comparator();
    /// Comparador VIRTUAL (`CNTV_*`) — o que `arm_arch_timer.c` arma num boot EL1 SEM hipervisor
    /// (`is_hyp_mode_available()` falso → `ARCH_TIMER_VIRT_PPI`, lê `CNTVCT_EL0`). Achado real da
    /// F11 (Linux 6.18 do Raspberry Pi 3 em EL1): o kernel lia `CNTVCT_EL0` antes de qualquer
    /// `CNTP_*`. Sem `CNTVOFF_EL2` modelado (offset 0), o contador virtual é o próprio contador.
    private final Comparator virtual = new Comparator();

    /// Um comparador de timer genérico (`CNTx_CTL`/`CNTx_CVAL`/`CNTx_TVAL`).
    private final class Comparator {
        private long compareValue;
        private boolean enabled;
        private boolean masked;

        boolean istatusSet() {
            return counter >= compareValue;
        }

        boolean irqPending() {
            return enabled && !masked && istatusSet();
        }

        long control() {
            return (enabled ? CTL_ENABLE : 0) | (masked ? CTL_IMASK : 0) | (istatusSet() ? CTL_ISTATUS : 0);
        }

        void setControl(long value) {
            enabled = (value & CTL_ENABLE) != 0;
            masked = (value & CTL_IMASK) != 0;
            // ISTATUS é somente-leitura (calculado), escrita nesse bit é ignorada.
        }

        long timerValue() {
            return (compareValue - counter) & TVAL_32BIT_MASK;
        }

        void setTimerValue(long value) {
            compareValue = counter + signExtend32(value);
        }
    }

    /// Avança o contador livre por `deltaCycles` ciclos de CPU emulados (1:1 — ver Javadoc da
    /// classe) e reavalia `ISTATUS`. Chamado pelo hospedeiro a cada fatia, mesmo padrão de
    /// `Bcm2835SystemTimer#advance`.
    public void advance(long deltaCycles) {
        counter += deltaCycles;
    }

    /// `true` quando o comparador FÍSICO expirou E o guest não mascarou (`ENABLE=1,IMASK=0,
    /// ISTATUS=1`) — a condição real que o BCM2836 roteia como PPI `nCNTPNSIRQ` para
    /// {@link Bcm2836LocalIntc}. `ISTATUS` em si (setado independente de `IMASK`, `ARM DDI 0487`
    /// pseudocódigo de `CNTP_CTL_EL0`) é visível ao guest em `CNTP_CTL_EL0`.
    public boolean physicalTimerIrqPending() {
        return physical.irqPending();
    }

    /// Mesma condição de {@link #physicalTimerIrqPending()} para o comparador VIRTUAL — PPI
    /// `nCNTVIRQ` do {@link Bcm2836LocalIntc}.
    public boolean virtualTimerIrqPending() {
        return virtual.irqPending();
    }

    @Override
    public boolean handles(Aarch64SystemRegisterId register) {
        return switch (register) {
            case CNTFRQ_EL0, CNTPCT_EL0, CNTP_TVAL_EL0, CNTP_CTL_EL0, CNTP_CVAL_EL0,
                 CNTVCT_EL0, CNTV_TVAL_EL0, CNTV_CTL_EL0, CNTV_CVAL_EL0 -> true;
            default -> false;
        };
    }

    @Override
    public long read(Aarch64SystemRegisterId register) {
        return switch (register) {
            case CNTFRQ_EL0 -> CNTFRQ_HZ;
            case CNTPCT_EL0, CNTVCT_EL0 -> counter;
            case CNTP_TVAL_EL0 -> physical.timerValue();
            case CNTP_CTL_EL0 -> physical.control();
            case CNTP_CVAL_EL0 -> physical.compareValue;
            case CNTV_TVAL_EL0 -> virtual.timerValue();
            case CNTV_CTL_EL0 -> virtual.control();
            case CNTV_CVAL_EL0 -> virtual.compareValue;
            default -> throw new UnsupportedOperationException(
                    "Bcm2836GenericTimer não atende: " + register);
        };
    }

    @Override
    public void write(Aarch64SystemRegisterId register, long value) {
        switch (register) {
            case CNTFRQ_EL0 -> { /* somente-leitura para este emulador — valor fixo do hardware real. */ }
            case CNTPCT_EL0, CNTVCT_EL0 -> { /* somente-leitura pelo guest em EL1 (hardware real também recusa). */ }
            case CNTP_TVAL_EL0 -> physical.setTimerValue(value);
            case CNTP_CTL_EL0 -> physical.setControl(value);
            case CNTP_CVAL_EL0 -> physical.compareValue = value;
            case CNTV_TVAL_EL0 -> virtual.setTimerValue(value);
            case CNTV_CTL_EL0 -> virtual.setControl(value);
            case CNTV_CVAL_EL0 -> virtual.compareValue = value;
            default -> throw new UnsupportedOperationException(
                    "Bcm2836GenericTimer não atende: " + register);
        }
    }

    private static long signExtend32(long value) {
        return (long) (int) value;
    }
}
