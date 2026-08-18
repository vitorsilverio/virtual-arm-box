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
3. **`raspi3-64` (task F11, 2026-08-18)** — AArch64, Raspberry Pi 3 (BCM2837), `kernel8.img`
   oficial pré-compilado do mesmo `raspberrypi/firmware` que deu os assets da F3 — **destrava o
   degrau aarch64 sem o toolchain `aarch64-linux-*`** que travava B6.6.6. Sem GIC (o Pi 3 real
   usa o mesmo controlador legado do BCM2835 + um IC local por-núcleo bem mais simples), sem
   PSCI (poda os `cpu@1..3` do DTB, 1 núcleo só). Rota preferida atual para chegar a um Linux
   arm64 rodando de verdade.
4. **`virt64` (B6.6.6 do arm-jitter, em espera)** — AArch64 `-M virt`, GIC + virtio-mmio + PSCI.
   Continua sendo o único caminho para **Windows on ARM** (exige UEFI/edk2) e o mais próximo de
   um guest arm64 "genérico" fora do ecossistema Raspberry Pi, mas fica sem prioridade enquanto
   a F11 (mais barata, sem GIC/PSCI/toolchain) não se esgotar. Retomar só se a F11 esbarrar em
   algo que só a máquina `virt` evitaria, ou se o usuário priorizar Windows on ARM diretamente.
5. **macOS — fora de escopo permanente.** Apple Silicon não tem documentação pública de
   plataforma, e o boot depende de hardware (Secure Enclave/iBoot) que não é emulável de
   forma legítima. Registrado aqui para nunca virar task.

Item 3 (F11) já tem spec própria (2026-08-18). Itens além do 3 continuam **[REFINAR]**: viram
spec própria quando o anterior fechar/o usuário priorizar.

## Armazenamento

Independente da escada de máquinas, o `virtual-arm-box` usa **disco virtual em formato
padrão, compatível com outras VMs**: `raw` e **QCOW2** (leitura e escrita), com VDI/VMDK/VHD
atendidos por `qemu-img convert`. Primeiro controlador: **PL181 MMCI (SD/MMC)** no
`versatilepb`. Isso é a task **F10** — toda máquina nova herda a mesma camada `DiskImage`.
