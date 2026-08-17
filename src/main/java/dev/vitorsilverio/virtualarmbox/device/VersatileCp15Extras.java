package dev.vitorsilverio.virtualarmbox.device;

import dev.vitorsilverio.armjitter.coprocessor.CoprocessorBus;
import dev.vitorsilverio.armjitter.memory.mmu.Cp15VmsaCoprocessor;

import java.util.Objects;

/// Decorator de {@link CoprocessorBus} com os dois pedaços de CP15 que um kernel Linux REAL usa
/// e que a RFC-SOFTMMU (§4, tabela de registradores do {@link Cp15VmsaCoprocessor}, B4.1.2) não
/// pediu — nenhum dos dois é um bug daquela task já fechada, são lacunas que só B4.1.5 descobre
/// ao rodar software de verdade em vez dos testes unitários da B4.1.1-B4.1.4. Mesmo padrão de
/// composição (decorator, não herança) documentado no Javadoc do `Cp15VmsaCoprocessor`: "quem
/// precisar de outros registradores CP15 ... encadeia outro `CoprocessorBus` decorator na frente,
/// igual ao precedente do `ArmboxCp15`".
///
/// ### 1. Registradores de identificação (`CRn=0`, `opcode1=0`)
/// `MIDR`/`CTR` em `CRm=0` — um kernel Linux real lê os dois já no stub de descompressão do
/// zImage (antes mesmo do kernel propriamente dito), para saber o tamanho de linha de cache e
/// escolher a entrada de `arch/arm/mm/proc-*.S` certa.
///
/// **Desvio frente à RFC-SOFTMMU decisão 2 (documentado, forçado pelo kernel real disponível)**:
/// a RFC escolheu ARM1176/ARMv6K como hospedeiro. Só que `testdata/vmlinuz-3.2.0-4-versatile`
/// (kernel REAL da Debian, ver `testdata/README.md` — sem toolchain para compilar um kernel
/// próprio) responde ao `MIDR` do ARM1176JZF-S (`0x410fb767`, valor real de
/// `target/arm/tcg/cpu32.c: arm1176_initfn` do QEMU) com `"Error: unrecognized/unsupported
/// processor variant"` e trava: esse build específico não tem `CONFIG_CPU_V6`/`proc-v6.S`
/// compilado, só o suporte ARMv5 do ARM926EJ-S — o núcleo que placas Versatile/PB REAIS
/// historicamente usavam (`-cpu arm1176` só existe como opção do QEMU, nunca foi hardware real).
/// Por isso esta classe devolve os valores REAIS do **ARM926EJ-S**
/// (`target/arm/tcg/cpu32.c: arm926_initfn` do QEMU) e o hospedeiro monta o core com
/// `ArmArchitecture.ARMV5TE` em vez de `ARMV6K` (ver `VersatilePbMachine`) — o formato de tabela
/// de páginas short-descriptor que `TranslatingAddressSpace`/`Cp15VmsaCoprocessor` (B4.1.1/
/// B4.1.2) implementam é o subconjunto clássico (domínios + AP de 2 bits, sem XN/APX/TEX) comum
/// a ARMv5 e ARMv6 — nenhuma mudança foi necessária nelas para este desvio.
///
/// ### 2. Idioma "test and clean cache" de `c7` (`CRn=7`, qualquer `CRm`/`opcode2`, sempre com
/// `Rt=PC` — ARM DDI 0100I §B4.2, ex.: `MRC p15,0,PC,c7,c14,3`)
/// `Cp15VmsaCoprocessor` (B4.1.2) devolve `0` (RAZ) para toda leitura de `c7`, exatamente como a
/// RFC pediu ("cache ops: NOP + barreira") — não modela cache real, então não há estado
/// observável de verdade. Mas o encoding `Rt=15` é especial no ARM real: em vez de escrever num
/// registrador comum, o valor lido atualiza os flags NZCV da CPSR diretamente — é assim que o
/// kernel espera por uma operação de manutenção de cache terminar (`bne` de volta ao `mrc`
/// enquanto `Z=0`). Devolver `0` bruto (`Z=0`) faz esse laço nunca terminar (trava real
/// observada ao rodar o kernel de `testdata/`, com `--arch=armv5te`, antes desta classe existir).
/// Hardware real e o `-cpu arm926`/`arm1176` do QEMU (sem cache modelada de verdade) sempre
/// reportam a operação como concluída de imediato — por isso esta classe devolve `Z=1`
/// (`0x4000_0000`, bit 30 da CPSR) para qualquer leitura de `c7`, e nada mais: nenhum código real
/// lê `c7` por outro motivo (as únicas leituras arquiteturalmente válidas de `c7` em ARMv5/6 SÃO
/// esses idiomas de teste-e-limpeza, sempre com `Rt=PC`).
public final class VersatileCp15Extras implements CoprocessorBus {
    private static final int CP15 = 15;
    private static final int CRN_ID = 0;
    private static final int OPCODE1_ID = 0;
    private static final int CRN_CACHE_MAINTENANCE = 7;

    private static final int CRM_MAIN_ID = 0;
    private static final int OPCODE2_MIDR = 0;
    private static final int OPCODE2_CTR = 1;
    private static final int OPCODE2_TCMTR = 2;
    private static final int OPCODE2_TLBTR = 3;

    /// `cpu->midr` do ARM926EJ-S (`0x41069265`) — casa com a máscara de `__arm926_proc_info`
    /// (`arch/arm/mm/proc-arm926.S`), o que faz o kernel escolher aquele `procinfo` (e não travar
    /// em "unrecognized/unsupported processor variant").
    private static final int MIDR_ARM926 = 0x4106_9265;
    /// `cpu->ctr` do ARM926EJ-S — tamanho de linha de cache real, usado pelos laços de flush do
    /// stub de descompressão do zImage.
    private static final int CTR_ARM926 = 0x01dd_20d2;

    /// Bit Z (bit 30) da CPSR — "operação de manutenção de cache concluída" no idioma `Rt=PC`
    /// de `c7` (ver Javadoc da classe, seção 2).
    private static final int CPSR_Z_FLAG = 1 << 30;

    private final CoprocessorBus delegate;

    public VersatileCp15Extras(CoprocessorBus delegate) {
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
                case OPCODE2_MIDR -> MIDR_ARM926;
                case OPCODE2_CTR -> CTR_ARM926;
                case OPCODE2_TCMTR, OPCODE2_TLBTR -> 0; // dummy (sem TCM/TLB legado modelado).
                default -> 0;
            };
        }
        if (crn == CRN_CACHE_MAINTENANCE) {
            return CPSR_Z_FLAG; // "concluído" imediato — ver Javadoc da classe, seção 2.
        }
        return delegate.read(coprocessor, opcode1, crn, crm, opcode2);
    }

    @Override
    public void write(int coprocessor, int opcode1, int crn, int crm, int opcode2, int value) {
        if (crn == CRN_ID && opcode1 == OPCODE1_ID && crm == CRM_MAIN_ID) {
            return; // registradores de identificação são somente-leitura em hardware real.
        }
        // c7 de escrita continua indo para o Cp15VmsaCoprocessor (B4.1.2): NOP observável, mesmo
        // comportamento de antes desta classe existir.
        delegate.write(coprocessor, opcode1, crn, crm, opcode2, value);
    }

    // ── MCRR/MRRC (F3, sessão de decode) ────────────────────────────────────────
    // Este decorator não intercepta nenhum registrador de transferência DUPLA — repassa sempre
    // para o delegate ({@link Cp15VmsaCoprocessor}). Sem estes 3 overrides, o default de
    // {@link CoprocessorBus#handlesDouble} herdaria de {@link #handles(int)} (que devolve `true`
    // para QUALQUER coisa em CP15, inclusive MCRR/MRRC), e os defaults de `readDouble`/
    // `writeDouble` lançariam `UnsupportedOperationException` em vez de delegar — mesmo bug real
    // corrigido em `Bcm2835Cp15Extras`.
    @Override
    public boolean handlesDouble(int coprocessor, int opcode1, int crm) {
        return delegate.handlesDouble(coprocessor, opcode1, crm);
    }

    @Override
    public long readDouble(int coprocessor, int opcode1, int crm) {
        return delegate.readDouble(coprocessor, opcode1, crm);
    }

    @Override
    public void writeDouble(int coprocessor, int opcode1, int crm, int rt, int rt2) {
        delegate.writeDouble(coprocessor, opcode1, crm, rt, rt2);
    }
}
