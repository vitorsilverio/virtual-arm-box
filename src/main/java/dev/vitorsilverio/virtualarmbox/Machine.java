package dev.vitorsilverio.virtualarmbox;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.jit.JitRuntime;

/// Uma máquina virtual completa (CPU + MMU + periféricos + protocolo de boot) hospedada
/// sobre o `arm-jitter`.
///
/// O contrato é deliberadamente mínimo — é o denominador comum entre placas muito
/// diferentes (`versatilepb`, Raspberry Pi, `virt` AArch64): o hospedeiro empurra o tempo
/// em fatias e observa/alimenta o console. Tudo que for específico de uma placa (mapa de
/// memória, IDs de IRQ, formato do kernel, ATAGs vs Device Tree) fica na implementação e
/// NÃO sobe para cá.
public interface Machine {

    /// Executa uma fatia de tempo: um lote fixo de blocos da CPU seguido do atendimento
    /// dos periféricos (temporizadores, controlador de interrupção, console).
    void runSlice();

    /// Entrega um byte ao console do guest, como se tivesse sido digitado no terminal.
    void typeByte(int value);

    /// O núcleo ARM principal da máquina — para testes, depuração e o `GdbServer`.
    ArmCore core();

    /// O runtime JIT do núcleo principal — para testes e diagnóstico.
    JitRuntime runtime();
}
