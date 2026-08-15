package dev.vitorsilverio.linuxbox;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.decoder.CoprocessorDecoder;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.decoder.VfpDecoder;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.memory.PagedAddressSpace;
import dev.vitorsilverio.armjitter.memory.mmu.Cp15VmsaCoprocessor;
import dev.vitorsilverio.armjitter.memory.mmu.TranslatingAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import dev.vitorsilverio.linuxbox.boot.AtagsBuilder;
import dev.vitorsilverio.linuxbox.device.OpenBus;
import dev.vitorsilverio.linuxbox.device.Pl011Uart;
import dev.vitorsilverio.linuxbox.device.Pl190Vic;
import dev.vitorsilverio.linuxbox.device.Sp804DualTimer;
import dev.vitorsilverio.linuxbox.device.VersatileCp15Extras;

import java.io.OutputStream;

/// Hospedeiro `versatilepb`-like (`-M versatilepb -cpu arm1176` do QEMU, RFC-SOFTMMU decisão
/// 2) para a tarefa B4.1.5: RAM + PL011 (console) + SP804 (temporizador) + PL190 (VIC) sobre
/// {@link PagedAddressSpace} (C3), com MMU real via {@link TranslatingAddressSpace} +
/// {@link Cp15VmsaCoprocessor} (B4.1.1-B4.1.4, já prontos — usados como estão).
///
/// Mapa de memória e IDs de fonte de IRQ espelham `hw/arm/versatilepb.c` do QEMU (só o
/// subconjunto no "Inclui" da task: UART0, os dois pares SP804, o VIC primário — sem SIC,
/// GPIO, RTC, MMCI, AACI, CLCD, DMAC, watchdog, PCI, Ethernet, todos fora de escopo e
/// servidos por {@link OpenBus}).
///
/// ### Modelo de tempo/IRQ
/// Sem callbacks síncronos entre periférico → VIC → core: cada periférico só expõe
/// `irqAsserted()`, consultado por sondagem a cada fatia de {@link #RUN_SLICE_BLOCKS} blocos
/// do laço principal ({@link #runSlice}) — o mesmo padrão de "fatia" que o armbox usa, mas
/// com hardware real a atender entre elas (o armbox é user-mode puro, sem isso).
public final class VersatilePbMachine {
    /// `machine->ram_size` — 128MiB, o tamanho pedido pela task (versatilepb aceita até 256MiB;
    /// `versatile_init` do QEMU recusa acima disso: "memory cannot overlap with devices").
    public static final int RAM_SIZE_BYTES = 128 * 1024 * 1024;
    private static final int RAM_BASE = 0x0000_0000;

    /// `arch/arm/tools/mach-types`: `versatile_pb ARCH_VERSATILE_PB VERSATILE_PB 387` — o
    /// mesmo ID que `versatile_init(machine, 0x183)` (`vpb_init`) usa no QEMU.
    private static final int MACH_TYPE_VERSATILE_PB = 387;

    /// `KERNEL_ARGS_ADDR`/`KERNEL_LOAD_ADDR` de `hw/arm/boot.c` do QEMU (protocolo ARM Linux
    /// clássico de ATAGs: zImage acima de 32KiB do início da RAM, lista de ATAGs logo no
    /// início).
    private static final int ATAGS_BASE = RAM_BASE + 0x100;
    private static final int KERNEL_LOAD_ADDR = RAM_BASE + 0x0001_0000;
    /// `hw/arm/boot.c`: `MIN(ram_size / 2, 128MiB)` — para 128MiB de RAM, a metade (64MiB).
    private static final int INITRD_LOAD_ADDR = RAM_BASE + RAM_SIZE_BYTES / 2;

    private static final int UART0_BASE = 0x101f_1000;
    private static final int SP804_TIMER01_BASE = 0x101e_2000;
    private static final int SP804_TIMER23_BASE = 0x101e_3000;
    private static final int VIC_BASE = 0x1014_0000;

    /// Fontes de IRQ no VIC primário (`hw/arm/versatilepb.c`: `pic[n] = ...`).
    private static final int IRQ_TIMER01 = 4;
    private static final int IRQ_TIMER23 = 5;
    private static final int IRQ_UART0 = 12;

    /// Páginas de 4KiB no barramento físico — precisa casar com o alinhamento real dos
    /// periféricos do versatilepb (`0x101f1000`, `0x101e2000`, ... só alinhados a 4KiB, não a
    /// potências maiores); coincide com a granularidade fixa da TLB da MMU, mas são conceitos
    /// independentes (esta é a tabela do barramento FÍSICO, por baixo do wrapper de tradução).
    private static final int PHYSICAL_PAGE_SHIFT = 12;

    private static final int BLOCK_CACHE_ENTRIES = 8192;
    private static final int HOT_THRESHOLD = 3;
    /// Blocos por fatia do laço principal — ver Javadoc da classe ("Modelo de tempo/IRQ").
    private static final int RUN_SLICE_BLOCKS = 256;

    /// Boot handoff clássico (`Documentation/arch/arm/booting.rst`): SVC, ARM state, IRQ/FIQ
    /// mascaradas.
    private static final int REGISTER_R0 = 0;
    private static final int REGISTER_R1 = 1;
    private static final int REGISTER_R2 = 2;

    /// Preset do core do guest: **ARM926EJ-S com a VFP9-S opcional presente** — a mesma
    /// configuração que o `-cpu arm926` do QEMU expõe (`arm926_initfn` liga `ARM_FEATURE_VFP`).
    ///
    /// A RFC-SOFTMMU decisão 2 pedia ARM1176/ARMv6K, mas o kernel REAL disponível
    /// (`testdata/vmlinuz-3.2.0-4-versatile`) só tem `proc-info` ARMv5 compilado — ver Javadoc de
    /// {@link VersatileCp15Extras} para o desvio completo. Daí `ARMV5TE` como base.
    ///
    /// **Por que `VFPV2` em cima da base** (achado real de B4.1.5, sessão 2): o `busybox-armv5l`
    /// de `testdata/` contém prológos VFP reais (`VPUSH {d8-d14}`, `0xED2D8B0E`), não gateados por
    /// `HWCAP_VFP`. Sem esta feature o PID 1 recebe `SIGILL` na primeira função que os usa e o
    /// kernel morre em `Kernel panic - not syncing: Attempted to kill init!`. Não é um desvio de
    /// fidelidade: a VFP9-S é opcional no ARM926EJ-S real, e é justamente a variante COM VFP que o
    /// QEMU (o oráculo de periféricos desta task) modela.
    ///
    /// O kernel continua reportando `VFP support v0.3: not present`, porque a sondagem dele lê
    /// `FPSID` via `MRC p10,7,...` e o `VfpDecoder` do arm-jitter só decodifica `FPSCR`
    /// (`FPSID`/`FPEXC`/`MVFR*` estão explicitamente fora de escopo lá). A consequência prática é
    /// só o `HWCAP_VFP` ficar zerado no `auxv` — as instruções VFP em si executam, porque num
    /// ARMv5 não há `CPACR` gateando CP10/CP11 (só `FPEXC.EN`, que também não é modelado).
    private static final ArmArchitecture ARM926EJS_VFP_FEATURES =
            ArmArchitecture.extending(ArmArchitecture.ARMV5TE, "ARM926EJ-S+VFP9-S", ArmFeature.VFPV2);

    /// {@link #ARM926EJS_VFP_FEATURES} com os decoders plugados. A ordem espelha
    /// `ArmArchitecture.ARM11_MPCORE`: `VfpDecoder` ANTES de `CoprocessorDecoder`, senão o decoder
    /// genérico de coprocessador captura os encodings de CP10/CP11 antes da VFP vê-los.
    private static final ArmArchitecture ARM926EJS_VFP = ARM926EJS_VFP_FEATURES
            .withDecoderExtensions(java.util.List.of(
                    new VfpDecoder(ARM926EJS_VFP_FEATURES),
                    new CoprocessorDecoder()));

    /// Backend de execução do CPU core (mesmo enum conceitual do armbox, sem o TRUFFLE desta
    /// vez — fora do "Inclui" da task).
    public enum Backend {
        JIT, INTERPRETED, CHECK
    }

    private final ArmCore core;
    private final JitRuntime runtime;
    private final Pl011Uart uart;
    private final Sp804DualTimer timer01;
    private final Sp804DualTimer timer23;
    private final Pl190Vic vic;
    private long lastCycles;

    private VersatilePbMachine(ArmCore core, JitRuntime runtime, Pl011Uart uart,
                                Sp804DualTimer timer01, Sp804DualTimer timer23, Pl190Vic vic) {
        this.core = core;
        this.runtime = runtime;
        this.uart = uart;
        this.timer01 = timer01;
        this.timer23 = timer23;
        this.vic = vic;
    }

    /// Monta a máquina completa (RAM + periféricos + MMU + core) e já carrega `kernelZImage`/
    /// `initramfs` na RAM com a lista de ATAGs pronta para o `entry` do kernel.
    ///
    /// @param kernelZImage bytes do zImage (`testdata/vmlinuz-3.2.0-4-versatile`)
    /// @param initramfs    bytes do initramfs comprimido (`testdata/initramfs.cpio.gz`)
    /// @param cmdline      linha de comando do kernel (`console=ttyAMA0 rdinit=/init ...`)
    /// @param consoleOut   saída de host para o UART0 (console)
    /// @param backend      backend de execução do core
    public static VersatilePbMachine create(byte[] kernelZImage, byte[] initramfs, String cmdline,
                                             OutputStream consoleOut, Backend backend) {
        PagedAddressSpace physical = new PagedAddressSpace(PHYSICAL_PAGE_SHIFT, OpenBus.INSTANCE);
        physical.mapRam(RAM_BASE, new byte[RAM_SIZE_BYTES]);

        Pl011Uart uart = new Pl011Uart(consoleOut);
        Sp804DualTimer timer01 = new Sp804DualTimer();
        Sp804DualTimer timer23 = new Sp804DualTimer();
        Pl190Vic vic = new Pl190Vic();

        physical.mapHandler(UART0_BASE, Pl011Uart.REGION_SIZE, uart);
        physical.mapHandler(SP804_TIMER01_BASE, Sp804DualTimer.REGION_SIZE, timer01);
        physical.mapHandler(SP804_TIMER23_BASE, Sp804DualTimer.REGION_SIZE, timer23);
        physical.mapHandler(VIC_BASE, Pl190Vic.REGION_SIZE, vic);

        TranslatingAddressSpace mmu = new TranslatingAddressSpace(physical);

        // RFC-SOFTMMU decisão 2 pede ARM1176/ARMv6K, mas o kernel REAL disponível
        // (testdata/vmlinuz-3.2.0-4-versatile, sem toolchain para compilar um próprio) só tem
        // proc-info para ARMv5 (ARM926EJ-S) compilado — ver Javadoc de VersatileCp15Extras para o
        // desvio completo, documentado também em testdata/README.md.
        ArmArchitecture architecture = ARM926EJS_VFP;
        JitRuntime runtime = switch (backend) {
            case JIT -> JitRuntimeFactory.armThumb(BLOCK_CACHE_ENTRIES, HOT_THRESHOLD, architecture);
            case INTERPRETED ->
                    JitRuntimeFactory.interpretedArmThumb(BLOCK_CACHE_ENTRIES, HOT_THRESHOLD, architecture);
            case CHECK ->
                    JitRuntimeFactory.divergenceCheckingArmThumb(BLOCK_CACHE_ENTRIES, HOT_THRESHOLD, architecture);
        };

        ArmCore core = new ArmCore(mmu, SwiDispatcher.empty(), architecture);
        Cp15VmsaCoprocessor cp15 = new Cp15VmsaCoprocessor(mmu, core);
        // VersatileCp15Extras (CRn=0: MIDR/CTR) na frente do CP15 VMSA da B4.1.2 — mesmo padrão
        // de composição documentado naquela classe; ver seu Javadoc para por que isso não é um
        // bug do B4.1.2 (a RFC-SOFTMMU nunca pediu CRn=0) nem do desvio ARMv5 vs. ARMv6K.
        core.setCoprocessorBus(new VersatileCp15Extras(cp15));
        core.setMemoryAbortListener(cp15);
        // Terceiro gancho do CP15 (B4.1.5): modo privilegiado vs. usuário para os bits AP da
        // tabela de páginas. Sem ele o copy-on-write do fork() do Linux nunca falta.
        core.setModeChangeListener(cp15);

        loadBytes(physical, KERNEL_LOAD_ADDR, kernelZImage);
        loadBytes(physical, INITRD_LOAD_ADDR, initramfs);

        new AtagsBuilder(physical, ATAGS_BASE)
                .core()
                .memory(RAM_SIZE_BYTES, RAM_BASE)
                .initrd(INITRD_LOAD_ADDR, initramfs.length)
                .cmdline(cmdline)
                .end();

        core.configureExecutionState(KERNEL_LOAD_ADDR, CpuMode.SUPERVISOR, InstructionSet.ARM, true, true);
        core.setRegister(REGISTER_R0, 0);
        core.setRegister(REGISTER_R1, MACH_TYPE_VERSATILE_PB);
        core.setRegister(REGISTER_R2, ATAGS_BASE);

        return new VersatilePbMachine(core, runtime, uart, timer01, timer23, vic);
    }

    private static void loadBytes(PagedAddressSpace memory, int base, byte[] data) {
        for (int i = 0; i < data.length; i++) {
            memory.write8(base + i, data[i]);
        }
    }

    /// Entrega um byte de teclado (host) ao UART0.
    public void typeByte(int value) {
        uart.receiveByte(value);
    }

    /// Executa uma fatia do laço principal ({@link #RUN_SLICE_BLOCKS} blocos), avança os
    /// temporizadores pelos ciclos consumidos e reavalia a linha de IRQ agregada do VIC.
    public void runSlice() {
        core.runBlocks(runtime, RUN_SLICE_BLOCKS);
        long cycles = core.cycles();
        long deltaCycles = cycles - lastCycles;
        lastCycles = cycles;
        if (deltaCycles > 0) {
            timer01.advance(deltaCycles);
            timer23.advance(deltaCycles);
        }
        vic.setIrqLine(IRQ_UART0, uart.irqAsserted());
        vic.setIrqLine(IRQ_TIMER01, timer01.irqAsserted());
        vic.setIrqLine(IRQ_TIMER23, timer23.irqAsserted());
        core.setInterruptLine(vic.irqAsserted());
    }

    /// Core interno (para inspeção/depuração e para o `GdbServer` do arm-jitter).
    public ArmCore core() {
        return core;
    }

    /// `JitRuntime` interno (para depuração fina — ex.: `core().runBlock(runtime())` em passos
    /// menores que {@link #RUN_SLICE_BLOCKS}).
    public JitRuntime runtime() {
        return runtime;
    }
}
