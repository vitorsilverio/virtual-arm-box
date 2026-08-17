package dev.vitorsilverio.virtualarmbox.device.bcm2835;

import dev.vitorsilverio.armjitter.coprocessor.CoprocessorBus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Testa {@link Bcm2835Cp14Extras} isoladamente, com um delegate falso — mesmo achado documentado
/// no Javadoc da classe: sem reivindicar CP14, `MRC p14,0,Rd,c0,c0,0` (`DBGDIDR`) vira `UNDEFINED`
/// dentro de `init_hw_breakpoint()`, matando a tarefa idle. `DBGDIDR=0` (RAZ) reproduz o mesmo
/// comportamento do oráculo QEMU (`arm1176_initfn` não seta `dbgdidr`, campo fica zerado).
class Bcm2835Cp14ExtrasTest {
    private static final int CP14 = 14;
    private static final int CP15 = 15;
    private static final int CRN_DBGDIDR = 0;
    private static final int CRM_DBGDIDR = 0;
    private static final int OPCODE1_DBGDIDR = 0;
    private static final int OPCODE2_DBGDIDR = 0;

    private static final class RecordingDelegate implements CoprocessorBus {
        boolean readCalled;
        boolean writeCalled;
        boolean handlesDoubleCalled;
        boolean readDoubleCalled;
        boolean writeDoubleCalled;

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
            return coprocessor == CP15;
        }

        @Override
        public long readDouble(int coprocessor, int opcode1, int crm) {
            readDoubleCalled = true;
            return 0x5678L;
        }

        @Override
        public void writeDouble(int coprocessor, int opcode1, int crm, int rt, int rt2) {
            writeDoubleCalled = true;
        }
    }

    @Test
    void claimsCoprocessor14() {
        Bcm2835Cp14Extras cp14 = new Bcm2835Cp14Extras(new RecordingDelegate());

        assertTrue(cp14.handles(CP14));
        assertTrue(cp14.handles(CP14, OPCODE1_DBGDIDR, CRN_DBGDIDR, CRM_DBGDIDR, OPCODE2_DBGDIDR));
    }

    @Test
    void dbgdidrIsZeroRaz() {
        Bcm2835Cp14Extras cp14 = new Bcm2835Cp14Extras(new RecordingDelegate());

        assertEquals(0, cp14.read(CP14, OPCODE1_DBGDIDR, CRN_DBGDIDR, CRM_DBGDIDR, OPCODE2_DBGDIDR),
                "DBGDIDR.ARCH=0 (\"não suportado\") — mesmo valor observável do oráculo QEMU");
    }

    @Test
    void cp14WritesAreIgnoredWithoutThrowing() {
        Bcm2835Cp14Extras cp14 = new Bcm2835Cp14Extras(new RecordingDelegate());

        cp14.write(CP14, OPCODE1_DBGDIDR, CRN_DBGDIDR, CRM_DBGDIDR, OPCODE2_DBGDIDR, 0xDEAD_BEEF);
        // não lança — WI, ver Javadoc da classe.
    }

    @Test
    void nonCp14TrafficDelegates() {
        RecordingDelegate delegate = new RecordingDelegate();
        Bcm2835Cp14Extras cp14 = new Bcm2835Cp14Extras(delegate);

        assertTrue(cp14.handles(CP15));
        assertFalse(cp14.handles(99));

        cp14.read(CP15, 0, 0, 0, 0);
        assertTrue(delegate.readCalled, "leitura de CP15 deve ser repassada ao delegate");

        cp14.write(CP15, 0, 0, 0, 0, 1);
        assertTrue(delegate.writeCalled, "escrita de CP15 deve ser repassada ao delegate");
    }

    /// Regressão (F3, sessão de decode `MCRR`/`MRRC`): este decorator é o mais externo da cadeia
    /// real em `Bcm2835Machine` — sem repassar `handlesDouble`/`readDouble`/`writeDouble` ao
    /// delegate, o default de `CoprocessorBus` (`false`, que NÃO delega automaticamente) engolia
    /// toda transferência DUPLA antes de alcançar `Cp15VmsaCoprocessor`, mesmo com o `c6` já
    /// implementado lá — o boot real continuava recebendo `UNDEFINED` em
    /// `MCRR p15,0,Rt,Rt2,c6` apesar dos testes unitários isolados de `Cp15VmsaCoprocessor`
    /// passarem (achado real: só apareceu rodando o kernel de verdade, não em teste de unidade).
    @Test
    void doubleTransferTrafficDelegates() {
        RecordingDelegate delegate = new RecordingDelegate();
        Bcm2835Cp14Extras cp14 = new Bcm2835Cp14Extras(delegate);

        assertTrue(cp14.handlesDouble(CP15, 0, 6));
        assertTrue(delegate.handlesDoubleCalled, "handlesDouble deve ser repassado ao delegate");

        assertEquals(0x5678L, cp14.readDouble(CP15, 0, 6));
        assertTrue(delegate.readDoubleCalled, "readDouble deve ser repassado ao delegate");

        cp14.writeDouble(CP15, 0, 6, 1, 2);
        assertTrue(delegate.writeDoubleCalled, "writeDouble deve ser repassado ao delegate");
    }
}
