package dev.vitorsilverio.virtualarmbox;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;

/// Sibling AArch64 de {@link Machine} (task F11, `raspi3-64`) — MESMO contrato, mas
/// `Aarch64Core` em vez de `ArmCore` (ver javadoc de {@link RunnableMachine} sobre por que os
/// dois mundos não compartilham um supertipo único). Não há um `runtime()` genérico exposto
/// aqui: diferente do mundo 32-bit (`JitRuntime` sempre presente, mesmo no backend
/// interpretado — {@code JitRuntimeFactory.interpretedArmThumb} devolve um), o backend
/// interpretado A64 roda direto sobre `Ir64BlockExecutor` (sem `JitRuntime64` nenhum
/// envolvido, mesmo padrão do precedente `armbox`/`Aarch64LinuxMachine`) — só o backend `JIT`
/// tem um `JitRuntime64` de verdade. Implementações que quiserem expor o runtime para
/// depuração o fazem por um getter próprio, não por este contrato comum.
public interface Machine64 extends RunnableMachine {

    /// O núcleo AArch64 principal da máquina — para testes e depuração.
    Aarch64Core core();
}
