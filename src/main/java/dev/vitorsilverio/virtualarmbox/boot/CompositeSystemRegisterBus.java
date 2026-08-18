package dev.vitorsilverio.virtualarmbox.boot;

import dev.vitorsilverio.armjitter.core64.Aarch64SystemRegisterBus;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;

/// Combina múltiplos {@link Aarch64SystemRegisterBus} num só, delegando ao PRIMEIRO que
/// {@link Aarch64SystemRegisterBus#handles} o registrador pedido — necessário porque
/// `Aarch64Core#setSystemRegisterBus` só aceita UM barramento, mas a task F11 precisa de DOIS
/// que cobrem subconjuntos DISJUNTOS de {@link Aarch64SystemRegisterId}:
/// `Aarch64VmsaSystemRegisters` (MMU/exceção, `arm-jitter` B6.6.3/B6.6.4) e
/// {@link dev.vitorsilverio.virtualarmbox.device.bcm2836.Bcm2836GenericTimer} (timer genérico,
/// B6.6.7). `invalidateTlbAll()` (`TLBI`) é repassado a TODOS os barramentos compostos — só
/// `Aarch64VmsaSystemRegisters` tem efeito real hoje, mas repassar sempre evita silenciosamente
/// ignorar um `TLBI` se um bus composto futuro também precisar dele.
public final class CompositeSystemRegisterBus implements Aarch64SystemRegisterBus {
    private final Aarch64SystemRegisterBus[] buses;

    public CompositeSystemRegisterBus(Aarch64SystemRegisterBus... buses) {
        this.buses = buses;
    }

    @Override
    public boolean handles(Aarch64SystemRegisterId register) {
        for (Aarch64SystemRegisterBus bus : buses) {
            if (bus.handles(register)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public long read(Aarch64SystemRegisterId register) {
        return busFor(register).read(register);
    }

    @Override
    public void write(Aarch64SystemRegisterId register, long value) {
        busFor(register).write(register, value);
    }

    @Override
    public void invalidateTlbAll() {
        for (Aarch64SystemRegisterBus bus : buses) {
            bus.invalidateTlbAll();
        }
    }

    private Aarch64SystemRegisterBus busFor(Aarch64SystemRegisterId register) {
        for (Aarch64SystemRegisterBus bus : buses) {
            if (bus.handles(register)) {
                return bus;
            }
        }
        throw new UnsupportedOperationException(
                "CompositeSystemRegisterBus: nenhum barramento composto atende " + register);
    }
}
