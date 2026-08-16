package dev.vitorsilverio.virtualarmbox;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.memory.PagedAddressSpace;
import dev.vitorsilverio.armjitter.memory.mmu.Cp15VmsaCoprocessor;
import dev.vitorsilverio.armjitter.memory.mmu.TranslatingAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import dev.vitorsilverio.virtualarmbox.boot.FdtPatcher;
import dev.vitorsilverio.virtualarmbox.boot.ZImageDecompressor;
import dev.vitorsilverio.virtualarmbox.device.OpenBus;
import dev.vitorsilverio.virtualarmbox.device.Pl011Uart;
import dev.vitorsilverio.virtualarmbox.device.bcm2835.Bcm2835ArmControlBlock;
import dev.vitorsilverio.virtualarmbox.device.bcm2835.Bcm2835Cp14Extras;
import dev.vitorsilverio.virtualarmbox.device.bcm2835.Bcm2835Cp15Extras;
import dev.vitorsilverio.virtualarmbox.device.bcm2835.Bcm2835Ic;
import dev.vitorsilverio.virtualarmbox.device.bcm2835.Bcm2835Mailbox;
import dev.vitorsilverio.virtualarmbox.device.bcm2835.Bcm2835SystemTimer;

import java.io.OutputStream;

/// Hospedeiro Raspberry Pi 1 / Zero (BCM2835, ARM1176JZF-S) para a task F3 — segundo degrau do
/// ROADMAP depois do {@link VersatilePbMachine} (B4.1.5): primeira validação de
/// {@code ArmArchitecture.ARM11_MPCORE} (ARMv6K + VFPv2, B5.2) rodando um kernel de SISTEMA REAL
/// (até então só validado em user-mode pelo armbox) e primeiro boot por Device Tree em vez de
/// ATAGs.
///
/// Mapa de memória e nomes de registrador espelham `include/hw/arm/raspi_platform.h` +
/// `hw/arm/bcm2835_peripherals.c` do QEMU (oráculo desta task, mesmo padrão de transcrição da
/// B4.1.5) — só o subconjunto no "Inclui" da task: temporizador de sistema, controlador de
/// interrupção, mailbox/canal de propriedades (mínimo) e UART0 (console, reaproveitando
/// {@link Pl011Uart} sem alterá-lo — é o mesmo bloco de IP ARM PrimeCell). GPIO/AUX/DMA/SD/USB/
/// clock manager (CPRMAN) ficam em {@link OpenBus} — **achado real, não lacuna silenciosa**: um
/// kernel REAL moderno (`testdata/raspi1/README.md`) só consegue registrar o console "de
/// verdade" (`ttyAMA0` via o driver `amba`/`pl011`, que exige um `clk` do CPRMAN + pinctrl GPIO
/// para o `probe()` suceder) depois de todo o subsistema de clock estar presente; sem CPRMAN o
/// kernel usa `earlycon` (poke direto de MMIO, sem depender de driver nenhum) do começo ao fim
/// do boot, o que já é suficiente para os marcos M1/M2 desta task mas **bloqueia M3** (sem
/// `/dev/ttyAMA0` real não há como um `getty` abrir um shell nele) — ver `README.md` desta
/// classe/task para o que a próxima sessão precisa (CPRMAN mínimo, só o clock do UART).
public final class Bcm2835Machine implements Machine {
    /// RAM do Model B rev 1 (256MiB) — o `.dtb` cru vem com `/memory@0/reg` zerado (achado real,
    /// ver Javadoc de {@link FdtPatcher}); este é o valor que o hospedeiro escreve ali.
    public static final long RAM_SIZE_BYTES = 256L * 1024 * 1024;
    private static final int RAM_BASE = 0x0000_0000;

    /// `arch/arm/tools/mach-types`: `bcm2708 MACH_TYPE_BCM2708 BCM2708 3138` — mesmo valor que
    /// `raspi_platform.h: MACH_TYPE_BCM2708` do QEMU usa em `s_base->binfo.board_id`. Repassado em
    /// `r1` por segurança (kernels com Device Tree o ignoram, mas custa nada estar certo).
    private static final int MACH_TYPE_BCM2708 = 3138;

    /// **Sessão 2/3 da F3 — mudança de estratégia**: a sessão 1 carregava o `zImage` cru em
    /// `RAM_BASE + 0x10000` (`KERNEL_LOAD_ADDR` de `hw/arm/boot.c` do QEMU) e deixava o PRÓPRIO
    /// stub de descompressão do kernel (`head.S`/`misc.c`, `inflate()` do zlib) rodar dentro do
    /// guest — correto, mas caro demais (~750 milhões de ciclos medidos, boot nunca terminava
    /// num orçamento de sessão/CI razoável, ver `Raspi1BootTest`/achado M1 da sessão 1). Esta
    /// sessão descomprime o `kernel.img` no HOST ({@link ZImageDecompressor}) e carrega a imagem
    /// JÁ DESCOMPRIMIDA direto no endereço de link que o `stext` do kernel espera —
    /// {@link ZImageDecompressor#TEXT_OFFSET} (`0x8000`, o `AUTO_ZRELADDR` calculado pelo stub
    /// real para um zImage carregado bem abaixo de 128MiB, ver Javadoc daquela classe). O
    /// `KERNEL_LOAD_ADDR` do `zImage` cru não é mais usado para carga nem para o handoff — só o
    /// endereço descomprimido abaixo.
    private static final int DECOMPRESSED_KERNEL_LOAD_ADDR = RAM_BASE + ZImageDecompressor.TEXT_OFFSET;
    /// `hw/arm/boot.c`: `MIN(ram_size / 2, 128MiB)` — para 256MiB de RAM, a metade (128MiB).
    private static final int INITRD_LOAD_ADDR = RAM_BASE + 128 * 1024 * 1024;
    /// Alinhamento do DTB depois do initrd (`hw/arm/boot.c`: 4KiB para kernels de 32 bits).
    private static final int DTB_ALIGNMENT = 0x1000;

    private static final int ST_BASE = 0x2000_3000;
    /// Base da página que contém IC (`+0x200`) e mailboxes (`+0x800`) — ver Javadoc de
    /// {@link Bcm2835ArmControlBlock} sobre por que os dois compartilham um único mapeamento.
    private static final int ARM_CONTROL_BLOCK_BASE = 0x2000_B000;
    private static final int UART0_BASE = 0x2020_1000;

    /// `raspi_platform.h`: fontes GPU do `Bcm2835Ic` usadas por esta task. Os 4 comparadores do
    /// `Bcm2835SystemTimer` mapeiam 1:1 nas fontes GPU 0-3 (`hw/timer/bcm2835_systmr.c` do QEMU,
    /// mesma fonte transcrita por aquela classe) — **achado real** (sessão de fechamento do M2):
    /// o `.dtb` real desta task declara `timer@7e003000: interrupts = <1 0>,<1 1>,<1 2>,<1 3>;`
    /// com `compatible = "brcm,bcm2835-system-timer"`, o binding exato do driver mainline
    /// `drivers/clocksource/bcm2835_timer.c`, cujo `DEFAULT_TIMER` é o comparador **3** (0/1 são
    /// reservados ao firmware VideoCore no hardware real, mesmo sem VideoCore aqui). Só
    /// encaminhar o comparador 0 (como esta classe fazia antes) nunca entrega o clockevent
    /// periódico que o kernel arma no comparador 3 — `jiffies` nunca avança e
    /// `calibrate_delay()` trava para sempre; ver Javadoc de `Raspi1BootTest`.
    private static final int INTERRUPT_TIMER0 = 0;
    private static final int INTERRUPT_TIMER1 = 1;
    private static final int INTERRUPT_TIMER2 = 2;
    private static final int INTERRUPT_TIMER3 = 3;
    private static final int INTERRUPT_UART0 = 57;
    /// `raspi_platform.h`: fonte ARM privada.
    private static final int INTERRUPT_ARM_MAILBOX = 1;

    private static final int PHYSICAL_PAGE_SHIFT = 12;
    private static final int BLOCK_CACHE_ENTRIES = 8192;
    private static final int HOT_THRESHOLD = 3;
    /// Blocos por fatia do laço principal — mesmo padrão de
    /// {@code VersatilePbMachine#RUN_SLICE_BLOCKS}.
    private static final int RUN_SLICE_BLOCKS = 256;

    private static final int REGISTER_R0 = 0;
    private static final int REGISTER_R1 = 1;
    private static final int REGISTER_R2 = 2;

    /// Backend de execução do CPU core (mesmo enum conceitual de
    /// {@code VersatilePbMachine.Backend}).
    public enum Backend {
        JIT, INTERPRETED, CHECK
    }

    private final ArmCore core;
    private final JitRuntime runtime;
    private final Pl011Uart uart;
    private final Bcm2835SystemTimer systemTimer;
    private final Bcm2835Ic ic;
    private final Bcm2835Mailbox mailbox;
    private long lastCycles;

    private Bcm2835Machine(ArmCore core, JitRuntime runtime, Pl011Uart uart,
                           Bcm2835SystemTimer systemTimer, Bcm2835Ic ic, Bcm2835Mailbox mailbox) {
        this.core = core;
        this.runtime = runtime;
        this.uart = uart;
        this.systemTimer = systemTimer;
        this.ic = ic;
        this.mailbox = mailbox;
    }

    /// Monta a máquina completa (RAM + periféricos + MMU + core) e carrega `initramfs`/`dtb` na
    /// RAM, com `/chosen/bootargs` e `/memory@0/reg` do `dtb` já patcheados ({@link FdtPatcher})
    /// para `cmdline` e {@link #RAM_SIZE_BYTES}. `kernelZImage` é descomprimido no HOST
    /// ({@link ZImageDecompressor}, achado da sessão 2/3 da F3) antes de carregar — o guest nunca
    /// vê o `zImage` comprimido nem roda o `inflate()` caro do stub.
    ///
    /// @param kernelZImage bytes do zImage COMPRIMIDO (`testdata/raspi1/kernel.img`) — esta
    ///                     fábrica descomprime antes de carregar, ver {@link ZImageDecompressor}
    /// @param initramfs    bytes do initramfs comprimido
    /// @param dtb          bytes do `.dtb` cru (`testdata/raspi1/bcm2708-rpi-b.dtb`)
    /// @param cmdline      linha de comando do kernel — vai para `/chosen/bootargs`, não ATAGs
    /// @param consoleOut   saída de host para o UART0 (console)
    /// @param backend      backend de execução do core
    public static Bcm2835Machine create(byte[] kernelZImage, byte[] initramfs, byte[] dtb,
                                        String cmdline, OutputStream consoleOut, Backend backend) {
        PagedAddressSpace physical = new PagedAddressSpace(PHYSICAL_PAGE_SHIFT, OpenBus.INSTANCE);
        physical.mapRam(RAM_BASE, new byte[(int) RAM_SIZE_BYTES]);

        Pl011Uart uart = new Pl011Uart(consoleOut);
        Bcm2835SystemTimer systemTimer = new Bcm2835SystemTimer();
        Bcm2835Ic ic = new Bcm2835Ic();
        Bcm2835Mailbox mailbox = new Bcm2835Mailbox(physical);

        physical.mapHandler(UART0_BASE, Pl011Uart.REGION_SIZE, uart);
        physical.mapHandler(ST_BASE, Bcm2835SystemTimer.REGION_SIZE, systemTimer);
        physical.mapHandler(ARM_CONTROL_BLOCK_BASE, Bcm2835ArmControlBlock.REGION_SIZE,
                new Bcm2835ArmControlBlock(ic, mailbox));

        TranslatingAddressSpace mmu = new TranslatingAddressSpace(physical);

        ArmArchitecture architecture = ArmArchitecture.ARM11_MPCORE;
        JitRuntime runtime = switch (backend) {
            case JIT -> JitRuntimeFactory.armThumb(BLOCK_CACHE_ENTRIES, HOT_THRESHOLD, architecture);
            case INTERPRETED ->
                    JitRuntimeFactory.interpretedArmThumb(BLOCK_CACHE_ENTRIES, HOT_THRESHOLD, architecture);
            case CHECK ->
                    JitRuntimeFactory.divergenceCheckingArmThumb(BLOCK_CACHE_ENTRIES, HOT_THRESHOLD, architecture);
        };

        ArmCore core = new ArmCore(mmu, SwiDispatcher.empty(), architecture);
        Cp15VmsaCoprocessor cp15 = new Cp15VmsaCoprocessor(mmu, core);
        core.setCoprocessorBus(new Bcm2835Cp14Extras(new Bcm2835Cp15Extras(cp15)));
        core.setMemoryAbortListener(cp15);
        core.setModeChangeListener(cp15);

        byte[] decompressedKernel = ZImageDecompressor.decompress(kernelZImage);
        loadBytes(physical, DECOMPRESSED_KERNEL_LOAD_ADDR, decompressedKernel);
        loadBytes(physical, INITRD_LOAD_ADDR, initramfs);
        int dtbAddress = alignUp(INITRD_LOAD_ADDR + initramfs.length, DTB_ALIGNMENT);

        byte[] patchedDtb = FdtPatcher.withMemorySize(FdtPatcher.withBootargs(dtb, cmdline), RAM_SIZE_BYTES);
        loadBytes(physical, dtbAddress, patchedDtb);

        core.configureExecutionState(
                DECOMPRESSED_KERNEL_LOAD_ADDR, CpuMode.SUPERVISOR, InstructionSet.ARM, true, true);
        core.setRegister(REGISTER_R0, 0);
        core.setRegister(REGISTER_R1, MACH_TYPE_BCM2708);
        core.setRegister(REGISTER_R2, dtbAddress);

        return new Bcm2835Machine(core, runtime, uart, systemTimer, ic, mailbox);
    }

    private static int alignUp(int value, int alignment) {
        return (value + alignment - 1) & ~(alignment - 1);
    }

    private static void loadBytes(PagedAddressSpace memory, int base, byte[] data) {
        for (int i = 0; i < data.length; i++) {
            memory.write8(base + i, data[i]);
        }
    }

    @Override
    public void typeByte(int value) {
        uart.receiveByte(value);
    }

    @Override
    public void runSlice() {
        core.runBlocks(runtime, RUN_SLICE_BLOCKS);
        long cycles = core.cycles();
        long deltaCycles = cycles - lastCycles;
        lastCycles = cycles;
        if (deltaCycles > 0) {
            systemTimer.advance(deltaCycles);
        }
        ic.setGpuIrqLine(INTERRUPT_UART0, uart.irqAsserted());
        ic.setGpuIrqLine(INTERRUPT_TIMER0, systemTimer.irqAsserted(0));
        ic.setGpuIrqLine(INTERRUPT_TIMER1, systemTimer.irqAsserted(1));
        ic.setGpuIrqLine(INTERRUPT_TIMER2, systemTimer.irqAsserted(2));
        ic.setGpuIrqLine(INTERRUPT_TIMER3, systemTimer.irqAsserted(3));
        ic.setArmIrqLine(INTERRUPT_ARM_MAILBOX, mailbox.irqAsserted());
        core.setInterruptLine(ic.irqAsserted());
    }

    @Override
    public ArmCore core() {
        return core;
    }

    @Override
    public JitRuntime runtime() {
        return runtime;
    }
}
