package dev.vitorsilverio.virtualarmbox;

import dev.vitorsilverio.armjitter.codegen64.Asm64CodeEmitter;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.executor64.Ir64BlockExecutor;
import dev.vitorsilverio.armjitter.jit.ExecutionThreshold;
import dev.vitorsilverio.armjitter.jit64.BlockCache64;
import dev.vitorsilverio.armjitter.jit64.JitRuntime64;
import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.memory.PagedAddressSpace;
import dev.vitorsilverio.armjitter.memory.mmu.Aarch64VmsaSystemRegisters;
import dev.vitorsilverio.armjitter.memory.mmu.TranslatingAddressSpace64;
import dev.vitorsilverio.virtualarmbox.boot.CompositeSystemRegisterBus;
import dev.vitorsilverio.virtualarmbox.boot.FdtPatcher;
import dev.vitorsilverio.virtualarmbox.device.OpenBus;
import dev.vitorsilverio.virtualarmbox.device.Pl011Uart;
import dev.vitorsilverio.virtualarmbox.device.bcm2835.Bcm2835ArmControlBlock;
import dev.vitorsilverio.virtualarmbox.device.bcm2835.Bcm2835Cprman;
import dev.vitorsilverio.virtualarmbox.device.bcm2835.Bcm2835Ic;
import dev.vitorsilverio.virtualarmbox.device.bcm2835.Bcm2835Mailbox;
import dev.vitorsilverio.virtualarmbox.device.bcm2836.Bcm2836GenericTimer;
import dev.vitorsilverio.virtualarmbox.device.bcm2836.Bcm2836LocalIntc;

import java.io.OutputStream;

/// Hospedeiro Raspberry Pi 3 (BCM2837, AArch64) para a task F11 — primeiro sistema REAL sobre o
/// `Aarch64Core` (até então só validado em user-mode ELF64 estático pelo armbox, B6.2). Terceiro
/// degrau do ROADMAP depois de {@link VersatilePbMachine} (B4.1.5, ARMv7) e {@link Bcm2835Machine}
/// (F3, ARMv6K) — mesma estratégia geral (host = bootloader, Device Tree via {@link FdtPatcher}),
/// mas a PRIMEIRA vez que o host precisa hospedar `Aarch64Core` em vez de `ArmCore`.
///
/// **Decisão de escopo D1 (herdada de B6.6.7/F11 spec): sem GIC, sem PSCI, 1 núcleo só.** O
/// `.dtb` real (`bcm2710-rpi-3-b.dtb`) declara 4 nós `cpu@0..3` — os 3 secundários são REMOVIDOS
/// (não só desabilitados, {@link FdtPatcher#withNodeRemoved}) para que o kernel nunca tente
/// nenhum SMP bring-up (`brcm,bcm2836-smp`, mailbox de spin — fora do "Inclui").
///
/// **Mapa físico BCM2837** (`hw/arm/raspi.c` do QEMU, achado registrado na task): a base de
/// periféricos legados (`0x7Exxxxxx` na visão do VideoCore) é `0x3F00_0000` — DIFERENTE de
/// `0x2000_0000` do BCM2835/{@link Bcm2835Machine}. O bloco de periféricos LOCAIS por-núcleo
/// (`brcm,bcm2836-l1-intc`, {@link Bcm2836LocalIntc}) fica numa janela físicaTOTALMENTE separada
/// (`0x4000_0000`), fora do barramento de periféricos legado.
///
/// **Achado real desta task (decisão D2, resolvida sem tocar o `arm-jitter`)**: `Aarch64Core`
/// (B6.6.4/B6.6.7) só modela a transição EL0→EL1 de ABORT/IRQ — não um "modo EL1 persistente" que
/// um kernel real ocuparia durante toda a execução principal (fora de handlers). Como não existe
/// EL2 modelado e o protocolo de boot arm64 aceita entrada em EL1 sem hipervisor (`Documentation/
/// arch/arm64/booting.rst`: "EL2 (recomendado) OU EL1 não-seguro"), esta máquina força
/// {@code core.exceptionState().setInEl1(true)} ANTES do primeiro `runSlice()` — simula um
/// firmware que já entregou o kernel em EL1 (evita precisar de QUALQUER modelo de EL2, mesmo
/// espírito de "sem GIC/PSCI" do D1). **Pendência EXPLÍCITA, NÃO resolvida aqui**: com isso,
/// `CurrentEL`/`SP` relatam EL1 corretamente para a execução principal, mas
/// {@code Aarch64Core#enterIrq}/{@code #enterMemoryAbort} (B6.6.7/B6.6.4) SEMPRE saltam para os
/// offsets de vetor "lower EL, AArch64" (`+0x400`/`+0x480`) — CORRETOS só para uma transição
/// EL0→EL1 real. Um kernel já "em EL1" que tomasse uma exceção DELE MESMO esperaria o handler em
/// "current EL, SPx" (`+0x200`/`+0x280`), código diferente do handler `el0_*` que vive em
/// `+0x400`/`+0x480` no vetor real do Linux (que assume estar vindo de EL0 — `kernel_entry 0`,
/// troca ASID/`TTBR0`, etc.). **Se o boot travar/corromper especificamente na primeira IRQ
/// entregue, esta é a causa raiz mais provável** — resolver exigiria estender `Aarch64Core` com um
/// segundo par de offsets de vetor "current EL" (mudança de arquitetura no `arm-jitter`,
/// explicitamente fora do "Inclui" desta task; registrar como achado para o usuário priorizar).
///
/// **Achado real D3 (decisão de backend)**: `jit64`/{@code BlockCache64} (B6.4) é
/// EXPLICITAMENTE "sem invalidação por escrita/página" (nenhum consumidor A64 anterior tinha
/// código automodificável — o `armbox` roda ELF64 estáticos síncronos). Um kernel Linux real faz
/// "alternatives patching"/`jump_label`/`ftrace` (reescrita de código em tempo de boot) — o
/// backend {@link Backend#JIT} desta máquina NÃO tem nenhuma proteção contra isso e pode executar
/// bytecode compilado a partir de código JÁ SOBRESCRITO pelo guest. Registrado aqui como
/// LIMITAÇÃO CONHECIDA (não uma mudança de escopo desta task — estender `BlockCache64` seria
/// mudança no `arm-jitter`, fora do "Inclui"); o backend {@link Backend#INTERPRETED} não sofre
/// disso (sempre decodifica do zero a cada `step()`).
public final class Raspi364Machine implements Machine64 {
    /// `hw/arm/raspi.c` do QEMU (`raspi3ap`): base do barramento de periféricos legado do
    /// BCM2837 — DIFERENTE de `0x2000_0000` do BCM2835 ({@link Bcm2835Machine}).
    private static final long PERIPHERAL_BASE = 0x3F00_0000L;
    /// RAM utilizável: até a base de periféricos (deixa a janela de 16MiB `0x3F00_0000`-
    /// `0x4000_0000` livre, mesmo split que o hardware real usa — Pi 3 tem 1GiB físico, mas a
    /// janela de periféricos consome o topo do espaço de endereço de 32 bits baixo).
    public static final long RAM_SIZE_BYTES = PERIPHERAL_BASE;
    private static final int RAM_BASE = 0;

    /// Base dos periféricos LOCAIS por-núcleo (BCM2836+) — janela física SEPARADA do barramento
    /// legado acima (`QA7_rev3.4.pdf`), não um deslocamento de {@link #PERIPHERAL_BASE}.
    private static final int LOCAL_PERIPHERALS_BASE = 0x4000_0000;

    private static final int UART0_BASE = (int) (PERIPHERAL_BASE + 0x20_1000);
    private static final int CPRMAN_BASE = (int) (PERIPHERAL_BASE + 0x10_1000);
    /// Base da página que contém IC (`+0x200`) e mailboxes (`+0x800`) — mesmo deslocamento
    /// relativo de {@link Bcm2835Machine#ARM_CONTROL_BLOCK_BASE}, só a base de periféricos muda.
    private static final int ARM_CONTROL_BLOCK_BASE = (int) (PERIPHERAL_BASE + 0xB000);

    /// Nomes dos 3 núcleos secundários no `.dtb` real (`bcm2710-rpi-3-b.dtb`) — removidos por
    /// completo (D1), não só desabilitados.
    private static final String[] SECONDARY_CPU_NODES = {"cpu@1", "cpu@2", "cpu@3"};
    /// `sdhost` legado (mesmo nó/mesma razão de {@link Bcm2835Machine#SDHOST_NODE_NAME}).
    private static final String SDHOST_NODE_NAME = "mmc@7e202000";
    /// Controlador SD/eMMC2 "de verdade" do Pi 3 (achado desta task — Pi 1 não tinha este nó) —
    /// sem cartão real, mesmo motivo de desabilitar {@link #SDHOST_NODE_NAME}.
    private static final String SDHCI_NODE_NAME = "mmc@7e300000";
    private static final String USB_NODE_NAME = "usb@7e980000";

    /// `raspi_platform.h`: fonte ARM privada da mailbox (mesmo valor de {@link Bcm2835Machine}).
    private static final int INTERRUPT_ARM_MAILBOX = 1;
    private static final int INTERRUPT_UART0 = 57;

    private static final int PHYSICAL_PAGE_SHIFT = 12;
    private static final int DTB_ALIGNMENT = 0x1000;
    private static final int BLOCK_CACHE_ENTRIES = 8192;
    private static final int HOT_THRESHOLD = 3;
    private static final int RUN_SLICE_INSTRUCTIONS = 4096;
    /// Blocos JIT por fatia — mesma ordem de grandeza de {@code Bcm2835Machine#RUN_SLICE_BLOCKS},
    /// escolhida para que uma fatia do backend {@link Backend#JIT} faça uma quantidade de
    /// trabalho comparável a {@link #RUN_SLICE_INSTRUCTIONS} do interpretado (blocos tendem a ter
    /// poucas instruções cada nesta fase fria de execução).
    private static final int RUN_SLICE_BLOCKS = 256;

    /// Backend de execução — ver Javadoc da classe (achado D3) sobre a limitação de invalidação
    /// do backend {@link #JIT}.
    public enum Backend {
        JIT, INTERPRETED
    }

    private final Aarch64Core core;
    private final Ir64BlockExecutor coldExecutor = new Ir64BlockExecutor();
    private final JitRuntime64 jitRuntime;
    private final Pl011Uart uart;
    private final Bcm2835Ic legacyIc;
    private final Bcm2835Mailbox mailbox;
    private final Bcm2836GenericTimer genericTimer;
    private final Bcm2836LocalIntc localIntc;
    private long lastCycles;

    private Raspi364Machine(Aarch64Core core, JitRuntime64 jitRuntime, Pl011Uart uart,
                            Bcm2835Ic legacyIc, Bcm2835Mailbox mailbox,
                            Bcm2836GenericTimer genericTimer, Bcm2836LocalIntc localIntc) {
        this.core = core;
        this.jitRuntime = jitRuntime;
        this.uart = uart;
        this.legacyIc = legacyIc;
        this.mailbox = mailbox;
        this.genericTimer = genericTimer;
        this.localIntc = localIntc;
    }

    /// Monta a máquina completa e carrega `kernelImage`/`initramfs`/`dtb` na RAM.
    ///
    /// @param kernelImage bytes do `kernel8.img` REAL (Image AArch64 não comprimida — protocolo
    ///                    `Documentation/arch/arm64/booting.rst`, sem descompressão no host)
    /// @param initramfs   bytes do initramfs comprimido
    /// @param dtb         bytes do `.dtb` cru (`testdata/raspi3-64/bcm2710-rpi-3-b.dtb`)
    /// @param cmdline     linha de comando do kernel — vai para `/chosen/bootargs`
    /// @param consoleOut  saída de host para o UART0 (console)
    /// @param backend     backend de execução do core
    public static Raspi364Machine create(byte[] kernelImage, byte[] initramfs, byte[] dtb,
                                         String cmdline, OutputStream consoleOut, Backend backend) {
        PagedAddressSpace physical32 = new PagedAddressSpace(PHYSICAL_PAGE_SHIFT, OpenBus.INSTANCE);
        physical32.mapRam(RAM_BASE, new byte[(int) RAM_SIZE_BYTES]);

        Pl011Uart uart = new Pl011Uart(consoleOut);
        Bcm2835Ic legacyIc = new Bcm2835Ic();
        Bcm2835Mailbox mailbox = new Bcm2835Mailbox(physical32);
        Bcm2836GenericTimer genericTimer = new Bcm2836GenericTimer();
        Bcm2836LocalIntc localIntc = new Bcm2836LocalIntc(genericTimer);

        physical32.mapHandler(UART0_BASE, Pl011Uart.REGION_SIZE, uart);
        physical32.mapHandler(CPRMAN_BASE, Bcm2835Cprman.REGION_SIZE, new Bcm2835Cprman());
        physical32.mapHandler(ARM_CONTROL_BLOCK_BASE, Bcm2835ArmControlBlock.REGION_SIZE,
                new Bcm2835ArmControlBlock(legacyIc, mailbox));
        physical32.mapHandler(LOCAL_PERIPHERALS_BASE, Bcm2836LocalIntc.REGION_SIZE, localIntc);

        // AddressSpace64.wrapping (RFC-IR-64BIT §3.2): todo o mapa físico acima cabe folgado nos
        // 32 bits baixos (RAM+periféricos < 0x4000_1000) — reusa a MESMA `PagedAddressSpace`/os
        // MESMOS periféricos MMIO de 32 bits do resto do repo, sem duplicar nenhum, em vez de uma
        // `PagedAddressSpace64` que não existe hoje (achado real: nenhum hospedeiro A64 anterior
        // precisou de MMIO físico, só o armbox/ELF64 user-mode, que usa memória plana própria).
        AddressSpace64 physical64 = AddressSpace64.wrapping(physical32);
        TranslatingAddressSpace64 mmu = new TranslatingAddressSpace64(physical64);

        Aarch64Core core = new Aarch64Core(mmu);
        Aarch64VmsaSystemRegisters vmsaRegisters = new Aarch64VmsaSystemRegisters(mmu, core);
        core.setSystemRegisterBus(new CompositeSystemRegisterBus(vmsaRegisters, genericTimer));

        // D2 (ver Javadoc da classe): simula um firmware que já entrega o kernel em EL1 — este
        // emulador não modela EL2, e o protocolo de boot aceita entrada em EL1 não-seguro.
        core.exceptionState().setInEl1(true);

        long kernelLoadAddress = RAM_BASE + readTextOffset(kernelImage);
        loadBytes(physical32, (int) kernelLoadAddress, kernelImage);
        long initrdLoadAddress = RAM_BASE + RAM_SIZE_BYTES / 2;
        loadBytes(physical32, (int) initrdLoadAddress, initramfs);
        long dtbAddress = alignUp(initrdLoadAddress + initramfs.length, DTB_ALIGNMENT);

        byte[] patchedDtb = patchDtb(dtb, cmdline, initrdLoadAddress, initramfs.length);
        loadBytes(physical32, (int) dtbAddress, patchedDtb);

        core.setProgramCounter(kernelLoadAddress);
        core.setX(0, dtbAddress);
        core.setX(1, 0);
        core.setX(2, 0);
        core.setX(3, 0);

        JitRuntime64 jitRuntime = backend == Backend.JIT
                ? new JitRuntime64(new BlockCache64(), new Asm64CodeEmitter(),
                        new ExecutionThreshold(HOT_THRESHOLD))
                : null;

        return new Raspi364Machine(core, jitRuntime, uart, legacyIc, mailbox, genericTimer, localIntc);
    }

    private static byte[] patchDtb(byte[] dtb, String cmdline, long initrdStart, int initrdLength) {
        byte[] withMemory = FdtPatcher.withMemorySize(dtb, RAM_SIZE_BYTES);
        byte[] withBootargs = FdtPatcher.withBootargs(withMemory, cmdline);
        byte[] withInitrd = FdtPatcher.withInitrdRange(
                withBootargs, initrdStart, initrdStart + initrdLength);
        byte[] withoutSecondaryCpus = withInitrd;
        for (String node : SECONDARY_CPU_NODES) {
            withoutSecondaryCpus = FdtPatcher.withNodeRemoved(withoutSecondaryCpus, node);
        }
        byte[] withoutSdhost = FdtPatcher.withNodeDisabled(withoutSecondaryCpus, SDHOST_NODE_NAME);
        byte[] withoutSdhci = FdtPatcher.withNodeDisabled(withoutSdhost, SDHCI_NODE_NAME);
        return FdtPatcher.withNodeDisabled(withoutSdhci, USB_NODE_NAME);
    }

    /// `Documentation/arch/arm64/booting.rst`: cabeçalho de 64 bytes, `text_offset` em `u64` LE
    /// no deslocamento `8` — ver `testdata/raspi3-64/README.md` para a proveniência completa do
    /// layout (confirmado por inspeção de bytes real, não suposição).
    private static long readTextOffset(byte[] kernelImage) {
        long value = 0;
        for (int i = 0; i < Long.BYTES; i++) {
            value |= (kernelImage[8 + i] & 0xFFL) << (8 * i);
        }
        return value;
    }

    private static long alignUp(long value, int alignment) {
        return (value + alignment - 1) & ~((long) alignment - 1);
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
        long cyclesBefore = core.cycles();
        if (jitRuntime != null) {
            for (int i = 0; i < RUN_SLICE_BLOCKS; i++) {
                jitRuntime.execute(core.pc(), core);
            }
        } else {
            coldExecutor.run(core, RUN_SLICE_INSTRUCTIONS);
        }
        long deltaCycles = core.cycles() - cyclesBefore;
        if (deltaCycles > 0) {
            genericTimer.advance(deltaCycles);
        }
        legacyIc.setGpuIrqLine(INTERRUPT_UART0, uart.irqAsserted());
        legacyIc.setArmIrqLine(INTERRUPT_ARM_MAILBOX, mailbox.irqAsserted());
        localIntc.setLegacyIcIrqLine(legacyIc.irqAsserted());
        core.setInterruptLine(localIntc.irqAsserted());
    }

    @Override
    public Aarch64Core core() {
        return core;
    }
}
