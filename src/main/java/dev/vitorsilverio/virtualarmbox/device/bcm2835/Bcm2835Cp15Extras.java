package dev.vitorsilverio.virtualarmbox.device.bcm2835;

import dev.vitorsilverio.armjitter.coprocessor.CoprocessorBus;
import dev.vitorsilverio.armjitter.memory.mmu.Cp15VmsaCoprocessor;

import java.util.Objects;

/// Decorator de {@link CoprocessorBus} com os registradores CP15 que um kernel Linux REAL
/// (ARMv6, `CONFIG_CPU_V6`/`proc-v6.S`) lê no boot e que a RFC-SOFTMMU não modela — mesmo
/// precedente de `dev.vitorsilverio.virtualarmbox.device.VersatileCp15Extras` (B4.1.5) para o
/// `versatilepb`/ARM926EJ-S, agora para o ARM1176JZF-S real do BCM2835 (task F3).
///
/// ### 1. Registradores de identificação (`CRn=0`, `opcode1=0`, `CRm={0,1,2}`, esquema CPUID
/// ARMv6+)
/// `MIDR`/`CTR` (`CRm=0`) — o stub de descompressão do zImage e `__v6_setup`/`proc-v6.S` leem os
/// dois já no arranque, para reconhecer o processador (`proc_info_list.cpu_val`/`cpu_mask`) e
/// escolher o tamanho de linha de cache dos laços de flush. `ID_PFR0`/`ID_PFR1`/`ID_DFR0`/
/// `ID_AFR0`/`ID_MMFR0..3` (`CRm=1`) e `ID_ISAR0..4` (`CRm=2`) — **achado real da F3/sessão 2**:
/// `mmu.c: build_mem_type_table()` (chamado bem cedo em `paging_init()`/`setup_arch()`) lê
/// `ID_MMFR0` (`c0,c1,4`) para decidir os atributos de shareability/TEX da tabela de páginas;
/// sem esse registrador a leitura caía no `Cp15VmsaCoprocessor` genérico, que só conhece o
/// esquema `CRm=0` e lança UNDEFINED — mesma cascata de laço infinito de PREFETCH_ABORT
/// documentada no Javadoc de {@link Cp15VmsaCoprocessor} para `TPIDRURO`.
///
/// Valores REAIS do ARM1176JZF-S, todos de `target/arm/tcg/cpu32.c: arm1176_initfn` do QEMU (o
/// mesmo `MIDR` que apareceu no log do kernel ao rodar `-M raspi1ap -cpu arm1176` como oráculo:
/// `CPU: ARMv6-compatible processor [410fb767] revision 7`): `MIDR=0x410fb767`,
/// `CTR=0x01dd20d2`, `ID_PFR0=0x111`, `ID_PFR1=0x11`, `ID_DFR0=0x33`, `ID_AFR0=0`,
/// `ID_MMFR0=0x01130003`, `ID_MMFR1=0x10030302`, `ID_MMFR2=0x01222100`, `ID_MMFR3=0`
/// (não setado pelo `arm1176_initfn`, RAZ), `ID_ISAR0=0x0140011`, `ID_ISAR1=0x12002111`,
/// `ID_ISAR2=0x11231121`, `ID_ISAR3=0x01102131`, `ID_ISAR4=0x01141`, `ID_ISAR5=0` (idem, não
/// setado).
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

    /// `CRm=1`: registradores de característica do processador (`ID_PFR0..ID_MMFR3`).
    private static final int CRM_PROCESSOR_FEATURE = 1;
    /// `CRm=2`: registradores de conjunto de instruções (`ID_ISAR0..ID_ISAR5`).
    private static final int CRM_INSTRUCTION_SET_ATTR = 2;

    /// `cpu->midr` do ARM1176JZF-S (ver Javadoc da classe).
    private static final int MIDR_ARM1176 = 0x410f_b767;
    /// `cpu->ctr` do ARM1176JZF-S.
    private static final int CTR_ARM1176 = 0x01dd_20d2;

    /// `ID_PFR0..ID_MMFR3` (`c0,c1,{0..7}`) e `ID_ISAR0..ID_ISAR5` (`c0,c2,{0..5}`) do
    /// ARM1176JZF-S — índice = `opcode2`, ver Javadoc da classe para a fonte (QEMU
    /// `arm1176_initfn`). `ID_MMFR3`/`ID_ISAR5` não são setados por `arm1176_initfn` (RAZ, `0`).
    private static final int[] ID_PROCESSOR_FEATURE = {
            0x0000_0111, // ID_PFR0
            0x0000_0011, // ID_PFR1
            0x0000_0033, // ID_DFR0
            0x0000_0000, // ID_AFR0
            0x0113_0003, // ID_MMFR0
            0x1003_0302, // ID_MMFR1
            0x0122_2100, // ID_MMFR2
            0x0000_0000, // ID_MMFR3 (RAZ)
    };
    private static final int[] ID_INSTRUCTION_SET_ATTR = {
            0x0140_011, // ID_ISAR0
            0x1200_2111, // ID_ISAR1
            0x1123_1121, // ID_ISAR2
            0x0110_2131, // ID_ISAR3
            0x0001_141, // ID_ISAR4
            0x0000_0000, // ID_ISAR5 (RAZ)
    };

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
        if (coprocessor == CP15 && (isIdScheme(crn, opcode1) || crn == CRN_CACHE_MAINTENANCE)) {
            return true;
        }
        return delegate.handles(coprocessor, opcode1, crn, crm, opcode2);
    }

    @Override
    public int read(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
        if (isIdScheme(crn, opcode1)) {
            return switch (crm) {
                case CRM_MAIN_ID -> switch (opcode2) {
                    case OPCODE2_MIDR -> MIDR_ARM1176;
                    case OPCODE2_CTR -> CTR_ARM1176;
                    default -> 0; // OPCODE2_TCMTR/OPCODE2_TLBTR/reservados: RAZ.
                };
                case CRM_PROCESSOR_FEATURE -> arrayEntryOrZero(ID_PROCESSOR_FEATURE, opcode2);
                case CRM_INSTRUCTION_SET_ATTR -> arrayEntryOrZero(ID_INSTRUCTION_SET_ATTR, opcode2);
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
        if (isIdScheme(crn, opcode1)) {
            return; // registradores de identificação são somente-leitura em hardware real.
        }
        delegate.write(coprocessor, opcode1, crn, crm, opcode2, value);
    }

    /// `CRn=0`, `opcode1=0`, **qualquer `CRm`** — o esquema CPUID inteiro do ARMv6+ (ARM DDI
    /// 0100I §B4.1.51 e seguintes), incluindo sub-registradores AINDA não alocados pela
    /// arquitetura na revisão do ARM1176JZF-S. **Achado real (2º, depois de `ID_MMFR0`)**: a
    /// arquitetura ARM GARANTE que ler um sub-registrador de ID não alocado/reservado devolve um
    /// valor UNKNOWN (aqui, `0`) em vez de lançar UNDEFINED — é o mecanismo de compatibilidade
    /// futura do próprio esquema CPUID (ARM DDI 0100I §B4.1.51, nota sobre `CRm` reservados).
    /// `build_mem_type_table()` no boot real do raspi1 lê `c0,c3,4` (nenhum registrador nomeado
    /// nesta revisão da arquitetura) — sem esse RAZ, a leitura caía como UNDEFINED igual ao
    /// achado de `ID_MMFR0`, mesma cascata de PREFETCH_ABORT recorrente. Em vez de continuar
    /// listando `CRm` um a um à medida que aparecem, esta classe agora reivindica o esquema
    /// INTEIRO e devolve `0` para qualquer combinação não nomeada abaixo — o comportamento
    /// architecturally correto, não um palpite.
    private static boolean isIdScheme(int crn, int opcode1) {
        return crn == CRN_ID && opcode1 == OPCODE1_ID;
    }

    private static int arrayEntryOrZero(int[] values, int index) {
        return index >= 0 && index < values.length ? values[index] : 0;
    }
}
