# ROADMAP — virtual-arm-box

A escada realista de máquinas, em ordem de dependência.

1. **`versatilepb` (✅ feito)** — ARMv5TE, ATAGs, Linux 3.2 Debian pré-compilado + busybox.
   Prova a MMU/softmmu (épico B4.1) fim-a-fim.
2. **`raspi1` (task F3)** — ARM1176JZF-S/ARMv6K, BCM2835: mini-UART/PL011, timer do
   sistema, controlador de IRQ próprio, mailbox. Primeira máquina com **Device Tree** em vez
   de ATAGs, e primeira validação do preset `ARMV6K` num kernel de sistema real (hoje o
   ARMv6K só foi validado em user-mode, no armbox). Kernel e DTB são baixáveis prontos do
   repositório `raspberrypi/firmware` — **não** dependem de toolchain `arm-linux-*`, que é o
   bloqueio histórico de B4.0.3/B6.2/B6.6.6.
3. **`virt64` (bloqueado em B6.6.6 do arm-jitter)** — AArch64 `-M virt`, GIC + virtio-mmio
   + PSCI. É o único caminho para guests modernos: Linux arm64, **Android** (que é Linux +
   userspace) e, mais adiante, **Windows on ARM** — este último exige, além da máquina,
   firmware **UEFI** (edk2 `QEMU_EFI.fd`) e virtio de disco/rede. Enquanto B6.6.6 não fechar
   (falta kernel/toolchain `aarch64-linux-*` reais), esta linha não anda.
4. **macOS — fora de escopo permanente.** Apple Silicon não tem documentação pública de
   plataforma, e o boot depende de hardware (Secure Enclave/iBoot) que não é emulável de
   forma legítima. Registrado aqui para nunca virar task.

Cada item além do 2 é **[REFINAR]**: vira spec própria quando o anterior fechar.

## Armazenamento

Independente da escada de máquinas, o `virtual-arm-box` usa **disco virtual em formato
padrão, compatível com outras VMs**: `raw` e **QCOW2** (leitura e escrita), com VDI/VMDK/VHD
atendidos por `qemu-img convert`. Primeiro controlador: **PL181 MMCI (SD/MMC)** no
`versatilepb`. Isso é a task **F10** — toda máquina nova herda a mesma camada `DiskImage`.
