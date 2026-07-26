package dev.vitorsilverio.linuxbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/// CLI: `linuxbox [--interp|--check] [--cycles=N] <zImage> <initramfs.gz> ["cmdline"]`.
///
/// Roda o hospedeiro `versatilepb`-like ({@link VersatilePbMachine}, B4.1.5) até o número de
/// fatias/blocos pedido, encaminhando a saída do console (UART0) para `stdout`. O initramfs é
/// passado ao kernel AINDA COMPRIMIDO (`.gz`) — `populate_rootfs`/`unpack_to_rootfs` do
/// próprio kernel detecta o magic gzip e descomprime, exatamente como um `-initrd` do QEMU;
/// não há descompressão no host. Sem TTY interativo real ligado ao `stdin` do host nesta
/// versão (uso principal é o teste de boot-to-shell automatizado, ver
/// `VersatilePbBootTest`) — {@link VersatilePbMachine#typeByte} existe para quem quiser
/// alimentar comandos programaticamente.
public final class Main {
    private static final int DEFAULT_SLICE_COUNT = 20_000;

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        VersatilePbMachine.Backend backend = VersatilePbMachine.Backend.JIT;
        int sliceCount = DEFAULT_SLICE_COUNT;
        int index = 0;
        while (index < args.length && args[index].startsWith("--")) {
            String arg = args[index];
            if (arg.startsWith("--cycles=")) {
                sliceCount = Integer.parseInt(arg.substring("--cycles=".length()));
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

        VersatilePbMachine machine = VersatilePbMachine.create(
                kernel, initramfs, cmdline, System.out, backend);
        for (int i = 0; i < sliceCount; i++) {
            machine.runSlice();
        }
    }

    private static void usage() {
        System.err.println("uso: linuxbox [--interp|--check] [--cycles=N] "
                + "<zImage> <initramfs.gz> [\"cmdline\"]");
        System.exit(2);
    }
}
