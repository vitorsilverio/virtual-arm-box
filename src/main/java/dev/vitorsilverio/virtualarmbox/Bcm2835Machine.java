package dev.vitorsilverio.virtualarmbox;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.InvalidationAwareAddressSpace;
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
import dev.vitorsilverio.virtualarmbox.device.bcm2835.Bcm2835Cprman;
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
/// interrupção, mailbox/canal de propriedades (mínimo), UART0 (console, reaproveitando
/// {@link Pl011Uart} sem alterá-lo — é o mesmo bloco de IP ARM PrimeCell) e, desde a sessão de
/// fechamento do M3, {@link Bcm2835Cprman} mínimo. GPIO/AUX/DMA/SD/USB ficam em {@link OpenBus}.
///
/// **M3 — achado real via trace de boot (sessão de fechamento)**: a hipótese herdada de sessões
/// anteriores ("CPRMAN + pinctrl GPIO são necessários para o `probe()` do driver `amba`/`pl011`
/// suceder") estava **parcialmente errada**: o driver PL011 real já registrava `ttyAMA0` mesmo
/// sem NENHUM periférico de clock (`"20201000.serial: ttyAMA0 ... is a PL011 rev2"` aparecia no
/// log mesmo com `OpenBus` no lugar do CPRMAN) — GPIO/pinctrl nunca bloqueou nada, não foi
/// necessário nenhum stub de GPIO. O bloqueio real era outro: o driver de clock
/// `drivers/clk/bcm/clk-bcm2835.c` marca `plld` como `CLK_IS_CRITICAL` (preparado
/// incondicionalmente no `probe()` síncrono, mesmo sem consumidor real) e espera o bit de "PLL
/// travado" em `CM_LOCK` (`0x114`) ligar; sob `OpenBus` esse bit nunca liga, o driver estoura um
/// timeout (`"plld: couldn't lock PLL"`/`error -ETIMEDOUT`) e cai no mecanismo de *deferred
/// probe* do kernel — o `ttyAMA0` real só termina de registrar bem mais tarde, numa `workqueue`
/// assíncrona, DEPOIS que o PID 1 (`/init`) já abriu `/dev/console` e ficou preso no console
/// antigo (`earlycon`, que não processa entrada digitada). {@link Bcm2835Cprman} corrige isso
/// sem modelar PLL algum: `CM_LOCK` sempre reporta "todos os PLLs travados", fazendo o `probe()`
/// síncrono ter sucesso na primeira tentativa — ver Javadoc daquela classe para o detalhe
/// completo e por que isso bastou (nenhum GPIO/pinctrl foi implementado).
///
/// **M3 ainda NÃO fecha — bloqueio NOVO e DIFERENTE, descoberto DEPOIS do fix do CPRMAN acima**:
/// com `CM_LOCK` sempre "travado", o log do kernel real confirma que `plld`/`ttyAMA0` registram
/// sem erro (`ETIMEDOUT`/`couldn't lock PLL` desaparecem por completo) — mas, poucos segundos de
/// tempo simulado depois de `Run /init as init process`, o console é **inundado por um laço de
/// nova tentativa aparentemente sem fim** do driver `sdhost-bcm2835`/`mmc0`
/// (`"mmc0: Card stuck being busy! __mmc_poll_for_busy"` / `"sdhost-bcm2835 20202000.mmc: no
/// support for card's volts"` / `"mmc0: error -22 whilst initialising SDIO card"`, repetindo a
/// ~1,2s de tempo simulado por vez, indefinidamente) — comportamento ESPERADO em hardware real
/// (`mmc_rescan` reagenda a si mesmo procurando por hot-plug de cartão para sempre quando não há
/// cartão instalado; SD/MMC real está deliberadamente fora do "Inclui" desta task, servido por
/// `OpenBus` em `0x2020_2000`), mas que aqui **nunca para**, porque {@link Bcm2835SystemTimer}
/// converte ciclos-de-CPU-emulados em microssegundos numa proporção fixa
/// (`HOST_CYCLES_PER_MICROSECOND=4`) desacoplada do relógio de parede real — o tempo simulado do
/// kernel corre bem à frente do tempo real, então o laço de nova tentativa (que seria só um
/// evento ocasional e barato em hardware real) consome uma fração dominante e crescente do
/// console e, aparentemente, do tempo de CPU pelo resto do boot. Um harness de diagnóstico
/// temporário (removido antes do commit, mesmo precedente de sessões anteriores) confirmou que o
/// console cresce de forma linear e estável (~27 mil caracteres por milhão de fatias, sem
/// desacelerar) mas que nem o próprio banner do `/init` deste repositório
/// (`"virtual-arm-box: initramfs busybox pronta"`, um `echo` simples, sem nenhuma dependência de
/// hardware) nem o prompt do shell (`"/ #"`) apareceram em 20 milhões de fatias (~8 minutos
/// reais) — não é possível afirmar se o prompt eventualmente aparece dado tempo suficiente (a
/// extrapolação da taxa observada sugere entre 60-90 minutos reais para os 200 milhões de fatias
/// do orçamento atual de `Raspi1BootTest#MAX_SLICES`, muito além do que um `@Test` bloqueante
/// desta sessão conseguiu validar dentro do orçamento de tool-calls).
///
/// **Sessão de extensão do `FdtPatcher` (2026-08-17) — os dois nós `mmc@7e202000`/`usb@7e980000`
/// agora são desabilitados via `status = "disabled"`** ({@link FdtPatcher#withNodeDisabled},
/// aplicado abaixo em {@link #create}) — ao contrário do que esta seção presumia, a sobrescrita
/// de propriedade de tamanho diferente NÃO exigiu nenhuma extensão estrutural do `FdtPatcher`
/// (o caminho de sobrescrita genérico já suportava isso, só faltava expô-lo). Isso fechou o
/// retry infinito de `mmc0`/`sdhost` E, achado NOVO desta sessão, uma espera síncrona silenciosa
/// do driver `usb`/`dwc_otg` (`"state() pending due to 20980000.usb"`, sem nenhuma linha de log
/// periódica — ao contrário do `mmc0`, este bloqueio não inunda o console, só o congela). Com os
/// dois desabilitados, o boot volta a avançar e `"Run /init as init process"` reaparece — mas
/// **M3 continua NÃO fechando**: um TERCEIRO bloqueio, novo e diferente dos dois anteriores,
/// congela o console de novo IMEDIATAMENTE depois, desta vez sem nenhuma pista textual (nem
/// mensagem de espera, nem Oops) — nem o `echo` do próprio `/init` deste repositório (que não
/// depende de hardware nenhum) chega a aparecer em dezenas de milhões de fatias adicionais. Ver
/// Javadoc de `Raspi1BootTest` (seção "Sessão de extensão do FdtPatcher") para o relato completo
/// e o próximo passo recomendado (trace instrução-a-instrução a partir do ponto onde `"Run /init
/// as init process"` é impresso, para descobrir se é `WFI`-sem-IRQ, uma outra espera síncrona, ou
/// algo no próprio script de `init` do busybox reagindo à ausência dos dois dispositivos
/// desabilitados).
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
    /// `CPRMAN_OFFSET = 0x101000` em `raspi_platform.h` do QEMU, relativo à base de periféricos
    /// do Pi 1 (`0x2000_0000`) — ver Javadoc de {@link Bcm2835Cprman}.
    private static final int CPRMAN_BASE = 0x2010_1000;

    /// Nome do nó `sdhost` no `.dtb` real (`/soc/mmc@7e202000`, `compatible = "brcm,bcm2835-
    /// sdhost"`) — desabilitado via {@link FdtPatcher#withNodeDisabled} (ver Javadoc da classe e
    /// da task F3): sem cartão SD real (deliberadamente fora do "Inclui"), `mmc_rescan` faria
    /// retry infinito e inundaria o console mais rápido do que o tempo real de teste consegue
    /// esperar, dado que {@link Bcm2835SystemTimer} comprime tempo-de-CPU-emulado numa proporção
    /// fixa desacoplada do relógio real.
    private static final String SDHOST_NODE_NAME = "mmc@7e202000";

    /// Nome do nó `usb` no `.dtb` real (`/soc/usb@7e980000`, `compatible = "brcm,bcm2708-usb"`,
    /// o `dwc_otg`) — desabilitado pelo mesmo motivo que {@link #SDHOST_NODE_NAME}, achado numa
    /// sessão de diagnóstico posterior da task F3: sem controlador USB real (deliberadamente
    /// fora do "Inclui" — ver "Não inclui" da spec), o driver real fica preso indefinidamente num
    /// `state() pending due to 20980000.usb` silencioso (nenhuma linha nova no console, ao
    /// contrário do retry ruidoso de `mmc0`) — o console fica com tamanho ESTÁVEL por dezenas de
    /// milhões de fatias seguidas, sem Oops nem progresso. Ao contrário de `mmc@7e202000`, este
    /// nó não tem propriedade `status` no `.dtb` cru (ausência == "okay" por definição do Device
    /// Tree) — {@link FdtPatcher#withNodeDisabled} cria a propriedade em vez de sobrescrever.
    private static final String USB_NODE_NAME = "usb@7e980000";

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
        physical.mapHandler(CPRMAN_BASE, Bcm2835Cprman.REGION_SIZE, new Bcm2835Cprman());

        TranslatingAddressSpace mmu = new TranslatingAddressSpace(physical);

        ArmArchitecture architecture = ArmArchitecture.ARM11_MPCORE;
        JitRuntime runtime = switch (backend) {
            case JIT -> JitRuntimeFactory.armThumb(BLOCK_CACHE_ENTRIES, HOT_THRESHOLD, architecture);
            case INTERPRETED ->
                    JitRuntimeFactory.interpretedArmThumb(BLOCK_CACHE_ENTRIES, HOT_THRESHOLD, architecture);
            case CHECK ->
                    JitRuntimeFactory.divergenceCheckingArmThumb(BLOCK_CACHE_ENTRIES, HOT_THRESHOLD, architecture);
        };

        // Achado real (sessão de investigação do silêncio pós-`kprobes:`): sem este decorador, uma
        // escrita do guest em uma página com código JIT já compilado (ex.: o "self-test" de
        // kprobes armando um breakpoint otimizado logo após "kprobe jump-optimization is
        // enabled") nunca invalidava o bloco em cache — o core continuava executando bytecode
        // JIT compilado a partir do código ANTIGO, divergindo do que o kernel real acabou de
        // escrever ali. Mesmo padrão já usado por `GbaConsole`/`Armbox` (`InvalidationAwareAddressSpace`
        // envolvendo o barramento que o `ArmCore` enxerga) — aqui envolve o `mmu` (não o `physical`
        // por baixo dele) porque blocos JIT são indexados pelo PC VIRTUAL que o core busca, o
        // mesmo espaço de endereço que `TranslatingAddressSpace#write32` recebe.
        AddressSpace jitAwareBus = new InvalidationAwareAddressSpace(mmu, runtime);

        ArmCore core = new ArmCore(jitAwareBus, SwiDispatcher.empty(), architecture);
        Cp15VmsaCoprocessor cp15 = new Cp15VmsaCoprocessor(mmu, core);
        core.setCoprocessorBus(new Bcm2835Cp14Extras(new Bcm2835Cp15Extras(cp15)));
        core.setMemoryAbortListener(cp15);
        core.setModeChangeListener(cp15);
        core.setExceptionEndiannessPolicy(cp15);

        byte[] decompressedKernel = ZImageDecompressor.decompress(kernelZImage);
        loadBytes(physical, DECOMPRESSED_KERNEL_LOAD_ADDR, decompressedKernel);
        loadBytes(physical, INITRD_LOAD_ADDR, initramfs);
        int dtbAddress = alignUp(INITRD_LOAD_ADDR + initramfs.length, DTB_ALIGNMENT);

        byte[] dtbWithBootargsAndMemory =
                FdtPatcher.withMemorySize(FdtPatcher.withBootargs(dtb, cmdline), RAM_SIZE_BYTES);
        byte[] dtbWithInitrd = FdtPatcher.withInitrdRange(
                dtbWithBootargsAndMemory, INITRD_LOAD_ADDR, INITRD_LOAD_ADDR + initramfs.length);
        byte[] dtbWithSdhostDisabled = FdtPatcher.withNodeDisabled(dtbWithInitrd, SDHOST_NODE_NAME);
        byte[] patchedDtb = FdtPatcher.withNodeDisabled(dtbWithSdhostDisabled, USB_NODE_NAME);
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
