package dev.vitorsilverio.virtualarmbox.device.bcm2835;

import dev.vitorsilverio.armjitter.coprocessor.CoprocessorBus;

import java.util.Objects;

/// Decorator de {@link CoprocessorBus} para o coprocessador de DEPURAÇÃO (CP14) do
/// ARM1176JZF-S — **achado real da F3, sessão de fechamento do M2, depois do fix de `SCTLR`
/// round-trip** (ver Javadoc de `Cp15VmsaCoprocessor` no `arm-jitter`): com o boot avançando além
/// de `unflatten_device_tree()`, o kernel Linux chega em `init_hw_breakpoint()` →
/// `hw_breakpoint_slots()` → `get_debug_arch()`, que lê `DBGDIDR` (`MRC p14,0,Rd,c0,c0,0`,
/// registrador de identificação de depuração). Nenhum {@link CoprocessorBus} deste host reivindica
/// o coprocessador 14 — o core entrega `UNDEFINED` ao guest, e como isso acontece dentro de
/// `start_kernel()` (processo `swapper`/idle, sem tratamento de sinal), o kernel morre com
/// `Kernel panic - not syncing: Attempted to kill the idle task!` em vez de simplesmente
/// desabilitar o suporte a hardware breakpoints.
///
/// **Confirmado contra o oráculo QEMU 8.0.0** (mesmo `kernel.img`+DTB+initramfs+cmdline desta
/// task, `-M raspi1ap -cpu arm1176`, ver `Raspi1BootTest`): o QEMU **também não implementa nenhum
/// campo de `DBGDIDR` para o `arm1176_initfn`** (`target/arm/tcg/cpu32.c` não atribui
/// `cpu->isar.dbgdidr`, diferente de CPUs mais novas como Cortex-A7/A15) — o campo fica no valor
/// padrão zerado da struct, e o log real mostra exatamente
/// `hw-breakpoint: debug architecture 0x0 unsupported.` (o kernel lê `DBGDIDR=0`, decodifica
/// `ARCH=0` como "não suportado" e segue o boot normalmente, sem tocar em nenhum outro registrador
/// de CP14). Este decorator reproduz o MESMO valor (`0`, RAZ) para `DBGDIDR` — não um palpite,
/// é o comportamento observável do oráculo para esta CPU específica.
///
/// Qualquer outro registrador de CP14 (o kernel não deveria tocar nenhum, já que decide "não
/// suportado" a partir de `DBGDIDR`, mas caso algum caminho raro leia/escreva algo mais) também
/// recebe RAZ/WI em vez de `UNDEFINED` — mesmo precedente de tolerância de
/// {@link Bcm2835Cp15Extras} para `c7` (manutenção de cache): reivindicar o coprocessador inteiro
/// em vez de listar registrador por registrador evita a mesma cascata de `UNDEFINED` recorrente já
/// documentada para `TPIDRURO`/`ID_MMFR0` no Javadoc de `Cp15VmsaCoprocessor`.
public final class Bcm2835Cp14Extras implements CoprocessorBus {
    private static final int CP14_DEBUG = 14;

    private final CoprocessorBus delegate;

    public Bcm2835Cp14Extras(CoprocessorBus delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public boolean handles(int coprocessor) {
        return coprocessor == CP14_DEBUG || delegate.handles(coprocessor);
    }

    @Override
    public boolean handles(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
        if (coprocessor == CP14_DEBUG) {
            return true;
        }
        return delegate.handles(coprocessor, opcode1, crn, crm, opcode2);
    }

    @Override
    public int read(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
        if (coprocessor == CP14_DEBUG) {
            return 0; // RAZ: DBGDIDR.ARCH=0 ("não suportado", ver Javadoc da classe) + demais regs.
        }
        return delegate.read(coprocessor, opcode1, crn, crm, opcode2);
    }

    @Override
    public void write(int coprocessor, int opcode1, int crn, int crm, int opcode2, int value) {
        if (coprocessor == CP14_DEBUG) {
            return; // WI: nenhum estado de depuração modelado nesta fase.
        }
        delegate.write(coprocessor, opcode1, crn, crm, opcode2, value);
    }
}
