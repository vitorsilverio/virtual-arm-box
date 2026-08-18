package dev.vitorsilverio.virtualarmbox;

/// Contrato mínimo que o laço de emulação do {@link Main} realmente usa — extraído de
/// {@link Machine} pela task F11 para que uma máquina AArch64 ({@link Machine64}, `Aarch64Core`)
/// possa ser conduzida pelo MESMO laço genérico sem `Machine`/{@link Machine64} precisarem de um
/// supertipo comum para `core()`/`runtime()` (esses dois permanecem tipados por família — `ArmCore`
/// num, `Aarch64Core` no outro, G2/G3 do `arm-jitter`: os dois mundos não se misturam nem por um
/// supertipo "genérico o bastante"). Aditivo: {@link Machine} passou a ESTENDER esta interface em
/// vez de declarar os dois métodos por conta própria — toda implementação existente
/// ({@code VersatilePbMachine}/{@code Bcm2835Machine}) já os tinha, então nada muda para elas (G3).
public interface RunnableMachine {

    /// Executa uma fatia de tempo: um lote fixo de blocos da CPU seguido do atendimento
    /// dos periféricos (temporizadores, controlador de interrupção, console).
    void runSlice();

    /// Entrega um byte ao console do guest, como se tivesse sido digitado no terminal.
    void typeByte(int value);
}
