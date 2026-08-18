package dev.vitorsilverio.virtualarmbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/// CLI: `virtual-arm-box [--machine=versatilepb|raspi1] [--interp|--check] [--cycles=N]
/// <zImage> <initramfs.gz> [<dtb>] ["cmdline"]` — `<dtb>` só existe para `--machine=raspi1`
/// (Device Tree em vez de ATAGs, task F3).
///
/// Roda a {@link Machine} escolhida (default {@value #MACHINE_VERSATILEPB}, {@link
/// VersatilePbMachine}, B4.1.5; {@value #MACHINE_RASPI1}, {@link Bcm2835Machine}, F3) até o
/// número de fatias/blocos pedido, encaminhando a saída do console (UART0) para `stdout`. O
/// initramfs é passado ao kernel AINDA COMPRIMIDO (`.gz`) — `populate_rootfs`/
/// `unpack_to_rootfs` do próprio kernel detecta o magic gzip e descomprime, exatamente como um
/// `-initrd` do QEMU; não há descompressão no host. O `stdin` do host é drenado sem bloquear
/// para o UART0 do guest, então o shell `busybox` responde a comandos digitados;
/// {@link Machine#typeByte} existe para quem quiser alimentar comandos programaticamente (é o
/// que o teste faz).
public final class Main {
    private static final int DEFAULT_SLICE_COUNT = 20_000;

    /// Nomes de máquina disponíveis — ver {@link #createMachine}.
    private static final String MACHINE_VERSATILEPB = "versatilepb";
    private static final String MACHINE_RASPI1 = "raspi1";
    private static final String MACHINE_RASPI3_64 = "raspi3-64";

    private enum Backend {
        JIT, INTERPRETED, CHECK
    }

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        String machineName = MACHINE_VERSATILEPB;
        Backend backend = Backend.JIT;
        int sliceCount = DEFAULT_SLICE_COUNT;
        int index = 0;
        while (index < args.length && args[index].startsWith("--")) {
            String arg = args[index];
            if (arg.startsWith("--cycles=")) {
                sliceCount = Integer.parseInt(arg.substring("--cycles=".length()));
            } else if (arg.startsWith("--machine=")) {
                machineName = arg.substring("--machine=".length());
            } else {
                switch (arg) {
                    case "--interp" -> backend = Backend.INTERPRETED;
                    case "--check" -> backend = Backend.CHECK;
                    default -> {
                        usage();
                        return;
                    }
                }
            }
            index++;
        }
        if (index + 1 >= args.length) {
            usage();
            return;
        }

        RunnableMachine machine;
        try {
            machine = createMachine(machineName, args, index, backend);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        for (int i = 0; i < sliceCount; i++) {
            machine.runSlice();
            if (i % STDIN_POLL_INTERVAL_SLICES == 0) {
                pumpStdin(machine);
            }
        }
    }

    private static RunnableMachine createMachine(String machineName, String[] args, int index, Backend backend)
            throws IOException {
        byte[] kernel = Files.readAllBytes(Path.of(args[index]));
        byte[] initramfs = Files.readAllBytes(Path.of(args[index + 1]));
        return switch (machineName) {
            case MACHINE_VERSATILEPB -> {
                String cmdline = index + 2 < args.length
                        ? args[index + 2]
                        : "console=ttyAMA0 root=/dev/ram rdinit=/init";
                yield VersatilePbMachine.create(kernel, initramfs, cmdline, System.out,
                        toVersatilePbBackend(backend));
            }
            case MACHINE_RASPI1 -> {
                if (index + 2 >= args.length) {
                    throw new IllegalArgumentException(
                            "--machine=" + MACHINE_RASPI1 + " precisa de <zImage> <initramfs.gz> <dtb>");
                }
                byte[] dtb = Files.readAllBytes(Path.of(args[index + 2]));
                String cmdline = index + 3 < args.length
                        ? args[index + 3]
                        : "console=ttyAMA0,115200 earlycon root=/dev/ram rdinit=/init";
                yield Bcm2835Machine.create(kernel, initramfs, dtb, cmdline, System.out,
                        toBcm2835Backend(backend));
            }
            case MACHINE_RASPI3_64 -> {
                if (index + 2 >= args.length) {
                    throw new IllegalArgumentException(
                            "--machine=" + MACHINE_RASPI3_64 + " precisa de <kernel8.img> <initramfs.gz> <dtb>");
                }
                byte[] dtb = Files.readAllBytes(Path.of(args[index + 2]));
                String cmdline = index + 3 < args.length
                        ? args[index + 3]
                        : "console=ttyAMA0,115200 earlycon root=/dev/ram rdinit=/init";
                yield Raspi364Machine.create(kernel, initramfs, dtb, cmdline, System.out,
                        toRaspi364Backend(backend));
            }
            default -> throw new IllegalArgumentException(
                    "máquina desconhecida: " + machineName
                            + " (disponíveis: " + MACHINE_VERSATILEPB + ", " + MACHINE_RASPI1
                            + ", " + MACHINE_RASPI3_64 + ")");
        };
    }

    private static VersatilePbMachine.Backend toVersatilePbBackend(Backend backend) {
        return switch (backend) {
            case JIT -> VersatilePbMachine.Backend.JIT;
            case INTERPRETED -> VersatilePbMachine.Backend.INTERPRETED;
            case CHECK -> VersatilePbMachine.Backend.CHECK;
        };
    }

    private static Bcm2835Machine.Backend toBcm2835Backend(Backend backend) {
        return switch (backend) {
            case JIT -> Bcm2835Machine.Backend.JIT;
            case INTERPRETED -> Bcm2835Machine.Backend.INTERPRETED;
            case CHECK -> Bcm2835Machine.Backend.CHECK;
        };
    }

    /// Sem backend `CHECK` para `raspi3-64` (achado real, task F11): `jit64` (B6.4) não tem um
    /// emissor de divergência A64 equivalente a {@code DivergenceCheckingCodeEmitter} (32-bit) —
    /// nenhum consumidor A64 anterior precisou de um. `--check` lança aqui em vez de silenciosamente
    /// cair para outro backend.
    private static Raspi364Machine.Backend toRaspi364Backend(Backend backend) {
        return switch (backend) {
            case JIT -> Raspi364Machine.Backend.JIT;
            case INTERPRETED -> Raspi364Machine.Backend.INTERPRETED;
            case CHECK -> throw new IllegalArgumentException(
                    "--check não existe para --machine=" + MACHINE_RASPI3_64
                            + " (jit64 não tem emissor de divergência A64 ainda)");
        };
    }

    /// De quantas em quantas fatias o `stdin` do host é drenado para o UART0 do guest — sondagem
    /// não bloqueante (`available()`), para o laço de emulação nunca parar esperando teclado.
    private static final int STDIN_POLL_INTERVAL_SLICES = 64;

    /// Entrega ao UART0 o que já estiver disponível no `stdin` do host, sem bloquear: é isto que
    /// torna o shell `busybox` do guest realmente interativo pela linha de comando.
    private static void pumpStdin(RunnableMachine machine) throws IOException {
        while (System.in.available() > 0) {
            int typed = System.in.read();
            if (typed < 0) {
                return;
            }
            machine.typeByte(typed);
        }
    }

    private static void usage() {
        System.err.println("uso: virtual-arm-box [--machine=" + MACHINE_VERSATILEPB + "|" + MACHINE_RASPI1
                + "|" + MACHINE_RASPI3_64 + "] "
                + "[--interp|--check] [--cycles=N] <zImage|kernel8.img> <initramfs.gz> [<dtb>] [\"cmdline\"]");
        System.exit(2);
    }
}
