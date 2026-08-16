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
}
