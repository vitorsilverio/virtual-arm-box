package dev.vitorsilverio.virtualarmbox.device.bcm2835;

import dev.vitorsilverio.armjitter.coprocessor.CoprocessorBus;
import dev.vitorsilverio.armjitter.memory.mmu.Cp15VmsaCoprocessor;

import java.util.Objects;

/// Decorator de {@link CoprocessorBus} com os registradores CP15 que um kernel Linux REAL
/// (ARMv6, `CONFIG_CPU_V6`/`proc-v6.S`) lê no boot e que a RFC-SOFTMMU não modela — mesmo
/// precedente de `dev.vitorsilverio.virtualarmbox.device.VersatileCp15Extras` (B4.1.5) para o
/// `versatilepb`/ARM926EJ-S, agora para o ARM1176JZF-S real do BCM2835 (task F3).
///
/// ### 1. Registradores de identificação (`CRn=0`, `opcode1=0`, `CRm=0`)
/// `MIDR`/`CTR` — o stub de descompressão do zImage e `__v6_setup`/`proc-v6.S` leem os dois já
/// no arranque, para reconhecer o processador (`proc_info_list.cpu_val`/`cpu_mask`) e escolher
/// o tamanho de linha de cache dos laços de flush.
///
/// Valores REAIS do ARM1176JZF-S (`target/arm/tcg/cpu32.c: arm1176_initfn` do QEMU — o mesmo
/// `MIDR` que apareceu no log do kernel ao rodar `-M raspi1ap -cpu arm1176` como oráculo:
/// `CPU: ARMv6-compatible processor [410fb767] revision 7`): `MIDR=0x410fb767`,
/// `CTR=0x01dd20d2`.
///
/// ### 2. Idioma "test and clean cache" de `c7` (`CRn=7`, `Rt=PC`)
/// Mesmo idioma e mesma correção documentados em {@link VersatileCp15Extras} — ARMv6 usa o
/// mesmo encoding de manutenção de cache que ARMv5 para este idiom (`MRC p15,0,PC,c7,c14,3`
/// etc.), e {@link Cp15VmsaCoprocessor} devolve `0` (RAZ) para `c7`, o que travaria o laço
/// `bne` do kernel esperando o bit Z. Devolvido aqui `Z=1` (`0x4000_0000`) por construção
/// separada em vez de generalizar `VersatileCp15Extras` (que documenta ser específico do desvio
/// ARM926EJ-S do `versatilepb` — cada hospedeiro compõe o próprio decorator, mesmo padrão
/// aditivo já estabelecido).
public final class Bcm2835Cp15Extras implements CoprocessorBus {
    private static final int CP15 = 15;
    private static final int CRN_ID = 0;
    private static final int OPCODE1_ID = 0;
    private static final int CRN_CACHE_MAINTENANCE = 7;
    private static final int CRM_MAIN_ID = 0;
    private static final int OPCODE2_MIDR = 0;
    private static final int OPCODE2_CTR = 1;
    private static final int OPCODE2_TCMTR = 2;
    private static final int OPCODE2_TLBTR = 3;

    /// `cpu->midr` do ARM1176JZF-S (ver Javadoc da classe).
    private static final int MIDR_ARM1176 = 0x410f_b767;
    /// `cpu->ctr` do ARM1176JZF-S.
    private static final int CTR_ARM1176 = 0x01dd_20d2;

    private static final int CPSR_Z_FLAG = 1 << 30;

    private final CoprocessorBus delegate;

    public Bcm2835Cp15Extras(CoprocessorBus delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public boolean handles(int coprocessor) {
        return coprocessor == CP15;
    }

    @Override
    public boolean handles(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
        if (coprocessor == CP15
                && ((crn == CRN_ID && opcode1 == OPCODE1_ID && crm == CRM_MAIN_ID) || crn == CRN_CACHE_MAINTENANCE)) {
            return true;
        }
        return delegate.handles(coprocessor, opcode1, crn, crm, opcode2);
    }

    @Override
    public int read(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
        if (crn == CRN_ID && opcode1 == OPCODE1_ID && crm == CRM_MAIN_ID) {
            return switch (opcode2) {
                case OPCODE2_MIDR -> MIDR_ARM1176;
                case OPCODE2_CTR -> CTR_ARM1176;
                case OPCODE2_TCMTR, OPCODE2_TLBTR -> 0;
                default -> 0;
            };
        }
        if (crn == CRN_CACHE_MAINTENANCE) {
            return CPSR_Z_FLAG;
        }
        return delegate.read(coprocessor, opcode1, crn, crm, opcode2);
    }

    @Override
    public void write(int coprocessor, int opcode1, int crn, int crm, int opcode2, int value) {
        if (crn == CRN_ID && opcode1 == OPCODE1_ID && crm == CRM_MAIN_ID) {
            return;
        }
        delegate.write(coprocessor, opcode1, crn, crm, opcode2, value);
    }
}
