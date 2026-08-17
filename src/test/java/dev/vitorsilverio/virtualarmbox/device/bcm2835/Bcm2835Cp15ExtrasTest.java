package dev.vitorsilverio.virtualarmbox.device.bcm2835;

import dev.vitorsilverio.armjitter.coprocessor.CoprocessorBus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Testa {@link Bcm2835Cp15Extras} isoladamente, com um delegate falso — mesmo par de achados
/// documentados no Javadoc da classe/de `VersatileCp15Extras`: `MIDR`/`CTR` reais do
/// ARM1176JZF-S e o idioma `c7`/`Rt=PC` de manutenção de cache respondendo `Z=1`.
class Bcm2835Cp15ExtrasTest {
    private static final int CP15 = 15;
    private static final int CRN_ID = 0;
    private static final int OPCODE1_ID = 0;
    private static final int CRM_MAIN_ID = 0;
    private static final int OPCODE2_MIDR = 0;
    private static final int OPCODE2_CTR = 1;
    private static final int CRN_CACHE_MAINTENANCE = 7;
    private static final int CPSR_Z_FLAG = 1 << 30;

    private static final class RecordingDelegate implements CoprocessorBus {
        boolean readCalled;
        boolean writeCalled;
        boolean handlesDoubleCalled;

        @Override
        public boolean handles(int coprocessor) {
            return coprocessor == CP15;
        }

        @Override
        public boolean handles(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
            return coprocessor == CP15;
        }

        @Override
        public int read(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
            readCalled = true;
            return 0x1234;
        }

        @Override
        public void write(int coprocessor, int opcode1, int crn, int crm, int opcode2, int value) {
            writeCalled = true;
        }

        @Override
        public boolean handlesDouble(int coprocessor, int opcode1, int crm) {
            handlesDoubleCalled = true;
            return true;
        }
    }

    @Test
    void midrIsRealArm1176Value() {
        Bcm2835Cp15Extras extras = new Bcm2835Cp15Extras(new RecordingDelegate());
        assertEquals(0x410f_b767, extras.read(CP15, OPCODE1_ID, CRN_ID, CRM_MAIN_ID, OPCODE2_MIDR));
    }

    @Test
    void ctrIsRealArm1176Value() {
        Bcm2835Cp15Extras extras = new Bcm2835Cp15Extras(new RecordingDelegate());
        assertEquals(0x01dd_20d2, extras.read(CP15, OPCODE1_ID, CRN_ID, CRM_MAIN_ID, OPCODE2_CTR));
    }

    @Test
    void idMmfr0IsRealArm1176ValueUsedByBuildMemTypeTable() {
        // F3/sessão 2 (raspi1/ARMv6K): `mmu.c: build_mem_type_table()` lê `ID_MMFR0` (`c0,c1,4`)
        // bem cedo em `paging_init()` — sem esse registrador a leitura caía no
        // `Cp15VmsaCoprocessor` genérico (só conhece `CRm=0`) e lançava UNDEFINED, achado real
        // que travava o boot do raspi1 num laço infinito de PREFETCH_ABORT (ver Javadoc da
        // classe). Regressão: `ID_MMFR0` bate com `arm1176_initfn` do QEMU.
        Bcm2835Cp15Extras extras = new Bcm2835Cp15Extras(new RecordingDelegate());
        int idMmfr0 = extras.read(CP15, OPCODE1_ID, CRN_ID, /* CRm= */ 1, /* opcode2= */ 4);
        assertEquals(0x0113_0003, idMmfr0);
    }

    @Test
    void idIsarRegistersAreRealArm1176Values() {
        Bcm2835Cp15Extras extras = new Bcm2835Cp15Extras(new RecordingDelegate());
        assertEquals(0x0140_011, extras.read(CP15, OPCODE1_ID, CRN_ID, /* CRm= */ 2, /* opcode2= */ 0));
        assertEquals(0x1200_2111, extras.read(CP15, OPCODE1_ID, CRN_ID, /* CRm= */ 2, /* opcode2= */ 1));
    }

    @Test
    void unmodeledIdRegisterEntriesReadAsZero() {
        // ID_MMFR3 e ID_ISAR5 não são setados por `arm1176_initfn` — RAZ, não UNDEFINED.
        Bcm2835Cp15Extras extras = new Bcm2835Cp15Extras(new RecordingDelegate());
        assertEquals(0, extras.read(CP15, OPCODE1_ID, CRN_ID, /* CRm= */ 1, /* opcode2= */ 7));
        assertEquals(0, extras.read(CP15, OPCODE1_ID, CRN_ID, /* CRm= */ 2, /* opcode2= */ 5));
        assertTrue(extras.handles(CP15, OPCODE1_ID, CRN_ID, /* CRm= */ 1, /* opcode2= */ 7));
    }

    @Test
    void idRegistersAreReadOnly() {
        RecordingDelegate delegate = new RecordingDelegate();
        Bcm2835Cp15Extras extras = new Bcm2835Cp15Extras(delegate);
        extras.write(CP15, OPCODE1_ID, CRN_ID, CRM_MAIN_ID, OPCODE2_MIDR, 0xFFFFFFFF);
        assertFalse(delegate.writeCalled, "escrita em registrador de ID não deveria alcançar o delegate");
    }

    @Test
    void cacheMaintenanceReadsAsCompletedImmediately() {
        Bcm2835Cp15Extras extras = new Bcm2835Cp15Extras(new RecordingDelegate());
        int result = extras.read(CP15, 0, CRN_CACHE_MAINTENANCE, 14, 3);
        assertEquals(CPSR_Z_FLAG, result);
    }

    @Test
    void otherRegistersDelegateThrough() {
        RecordingDelegate delegate = new RecordingDelegate();
        Bcm2835Cp15Extras extras = new Bcm2835Cp15Extras(delegate);
        extras.write(CP15, 0, 2, 0, 0, 42); // TTBR0, não interceptado.
        assertTrue(delegate.writeCalled, "registrador fora do escopo desta classe deveria ir ao delegate");
    }

    /// Regressão (F3, sessão de decode `MCRR`/`MRRC`): esta classe não intercepta nenhuma
    /// transferência DUPLA — precisa repassar `handlesDouble` ao delegate (`Cp15VmsaCoprocessor`
    /// na cadeia real), senão o default de `CoprocessorBus` (`false`) engole a chamada antes de
    /// alcançar o `c6` implementado lá. Mesmo achado documentado em `Bcm2835Cp14ExtrasTest`.
    @Test
    void doubleTransferHandlesDelegatesThrough() {
        RecordingDelegate delegate = new RecordingDelegate();
        Bcm2835Cp15Extras extras = new Bcm2835Cp15Extras(delegate);

        assertTrue(extras.handlesDouble(CP15, 0, 6));
        assertTrue(delegate.handlesDoubleCalled, "handlesDouble deve ser repassado ao delegate");
    }
}
