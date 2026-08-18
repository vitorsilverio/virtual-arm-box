package dev.vitorsilverio.virtualarmbox;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.jit.JitRuntime;

/// Uma máquina virtual completa (CPU + MMU + periféricos + protocolo de boot) hospedada
/// sobre o `arm-jitter`.
///
/// O contrato é deliberadamente mínimo — é o denominador comum entre placas muito
/// diferentes (`versatilepb`, Raspberry Pi). Tudo que for específico de uma placa (mapa de
/// memória, IDs de IRQ, formato do kernel, ATAGs vs Device Tree) fica na implementação e
/// NÃO sobe para cá.
///
/// Máquinas **AArch64** (`raspi3-64`, task F11) implementam {@link Machine64} em vez desta
/// interface — `ArmCore`/`JitRuntime` (32-bit) não são o mesmo tipo de `Aarch64Core`/`JitRuntime64`
/// (G2/G3 do `arm-jitter`, ver javadoc de {@link RunnableMachine}). `runSlice()`/`typeByte()`
/// (o que o laço genérico de {@link Main} realmente precisa) vêm de {@link RunnableMachine},
/// estendida por esta interface — aditivo, G3: nenhuma implementação existente muda.
public interface Machine extends RunnableMachine {

    /// O núcleo ARM principal da máquina — para testes, depuração e o `GdbServer`.
    ArmCore core();

    /// O runtime JIT do núcleo principal — para testes e diagnóstico.
    JitRuntime runtime();
}
