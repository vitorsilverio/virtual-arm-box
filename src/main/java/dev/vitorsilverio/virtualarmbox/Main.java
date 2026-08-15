package dev.vitorsilverio.virtualarmbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/// CLI: `virtual-arm-box [--machine=versatilepb] [--interp|--check] [--cycles=N] <zImage>
/// <initramfs.gz> ["cmdline"]`.
///
/// Roda a {@link Machine} escolhida (default {@value #MACHINE_VERSATILEPB}, hoje a única
/// implementação, {@link VersatilePbMachine}, B4.1.5) até o número de fatias/blocos pedido,
/// encaminhando a saída do console (UART0) para `stdout`. O initramfs é passado ao kernel
/// AINDA COMPRIMIDO (`.gz`) — `populate_rootfs`/`unpack_to_rootfs` do próprio kernel detecta o
/// magic gzip e descomprime, exatamente como um `-initrd` do QEMU; não há descompressão no
/// host. O `stdin` do host é drenado sem bloquear para o UART0 do guest, então o shell
/// `busybox` responde a comandos digitados; {@link Machine#typeByte} existe para quem quiser
/// alimentar comandos programaticamente (é o que o teste faz).
public final class Main {
    private static final int DEFAULT_SLICE_COUNT = 20_000;

    /// Único nome de máquina disponível hoje — ver {@link #createMachine}.
    private static final String MACHINE_VERSATILEPB = "versatilepb";

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        String machineName = MACHINE_VERSATILEPB;
        VersatilePbMachine.Backend backend = VersatilePbMachine.Backend.JIT;
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
                    case "--interp" -> backend = VersatilePbMachine.Backend.INTERPRETED;
                    case "--check" -> backend = VersatilePbMachine.Backend.CHECK;
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
        byte[] kernel = Files.readAllBytes(Path.of(args[index]));
        byte[] initramfs = Files.readAllBytes(Path.of(args[index + 1]));
        String cmdline = index + 2 < args.length
                ? args[index + 2]
                : "console=ttyAMA0 root=/dev/ram rdinit=/init";

        Machine machine;
        try {
            machine = createMachine(machineName, kernel, initramfs, cmdline, backend);
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

    private static Machine createMachine(String machineName, byte[] kernel, byte[] initramfs,
            String cmdline, VersatilePbMachine.Backend backend) {
        return switch (machineName) {
            case MACHINE_VERSATILEPB ->
                    VersatilePbMachine.create(kernel, initramfs, cmdline, System.out, backend);
            default -> throw new IllegalArgumentException(
                    "máquina desconhecida: " + machineName + " (disponíveis: " + MACHINE_VERSATILEPB + ")");
        };
    }

    /// De quantas em quantas fatias o `stdin` do host é drenado para o UART0 do guest — sondagem
    /// não bloqueante (`available()`), para o laço de emulação nunca parar esperando teclado.
    private static final int STDIN_POLL_INTERVAL_SLICES = 64;

    /// Entrega ao UART0 o que já estiver disponível no `stdin` do host, sem bloquear: é isto que
    /// torna o shell `busybox` do guest realmente interativo pela linha de comando.
    private static void pumpStdin(Machine machine) throws IOException {
        while (System.in.available() > 0) {
            int typed = System.in.read();
            if (typed < 0) {
                return;
            }
            machine.typeByte(typed);
        }
    }

    private static void usage() {
        System.err.println("uso: virtual-arm-box [--machine=" + MACHINE_VERSATILEPB + "] "
                + "[--interp|--check] [--cycles=N] <zImage> <initramfs.gz> [\"cmdline\"]");
        System.exit(2);
    }
}
