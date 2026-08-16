# testdata/raspi1/ — kernel + DTB versionados (F3)

Mesmo espírito do `testdata/README.md` (versatilepb, B4.1.5): binários REAIS, baixados de
fontes públicas, URL e commit exatos documentados aqui, versionados no repo para
reprodutibilidade em CI/outra máquina.

## `kernel.img`

Kernel Linux ARMv6 pré-compilado oficial do Raspberry Pi Foundation, do repositório
`raspberrypi/firmware`:

```
https://github.com/raspberrypi/firmware/raw/12eeaa12865869b07db760f4bbb7507ec6f1976c/boot/kernel.img
```

Commit fixado (branch `stable` em 2026-08-15): `12eeaa12865869b07db760f4bbb7507ec6f1976c`
("Update kernel to 6.18.33"). `file` confirma: `Linux kernel ARM boot executable zImage
(little-endian)`. `sha256sum`: `797e581849d67bd073481cfe1716c36325431befcf470dd7730b740265890a0a`.

**Este é o `kernel.img` SEM sufixo** (ARMv6, Pi 1/Zero) — não `kernel7.img` (ARMv7/Pi 2) nem
`kernel8.img` (AArch64), que não servem para esta máquina (armadilha nº1 da spec).

**Achado real (M1 redefinido — ver `Raspi1BootTest`)**: este build oficial do Raspberry Pi
Foundation **não** tem `CONFIG_DEBUG_LL`/early-debug compilado no estágio de descompressão do
zImage — confirmado rodando o MESMO `kernel.img`+`.dtb` no `qemu-system-arm -M raspi1ap -cpu
arm1176 -m 512` instalado nesta máquina (`C:\Program Files\qemu\`) como oráculo: a mensagem
"Uncompressing Linux... done, booting the kernel." do enunciado da task **nunca aparece**, nem
no QEMU real nem seria de esperar aqui (kernel idêntico, mesmo estágio de descompressão). A
primeira saída observável de qualquer kernel real é `[    0.000000] Booting Linux on physical
CPU 0x0`, via o mecanismo `earlycon` do kernel (poke direto de UART a partir do `stdout-path`
do Device Tree, ANTES de qualquer driver `amba`/`pl011` real precisar sondar clock/pinctrl) —
descoberto testando `console=ttyAMA0,115200 earlycon` no mesmo oráculo QEMU. `Raspi1BootTest`
usa esse marcador equivalente em vez do texto literal do enunciado.

## `bcm2708-rpi-b.dtb`

Device Tree Blob do Raspberry Pi Model B (rev 1/2), mesmo commit do `kernel.img`:

```
https://github.com/raspberrypi/firmware/raw/12eeaa12865869b07db760f4bbb7507ec6f1976c/boot/bcm2708-rpi-b.dtb
```

`sha256sum`: `424224bfcc7b335b49f49052a00a183fc5d57532f06ad80bcb911d79f8441ffc`. `dtc`/`fdtdump`
confirmam: `Device Tree Blob version 17, ... boot CPU=0`.

**Achado real (por que o `FdtPatcher` reescreve `/memory@0/reg`, não só `bootargs`)**: o `.dtb`
cru vem com `reg = <0x0 0x0>` no nó `memory@0` — endereço 0, TAMANHO ZERO (confirmado por
inspeção de bytes, `od -A d -t x1`). Não é um bug do arquivo: no Raspberry Pi real é o
`start.elf` (firmware VideoCore proprietário, fora do "Não inclui" desta task) quem detecta a
RAM instalada e sobrescreve este campo em tempo de boot. Como este repo **é** o próprio
bootloader (mesma decisão de arquitetura do `versatilepb`, sem `start.elf`/`bootcode.bin`), é
`Bcm2835Machine`/`FdtPatcher` quem tem que fazer essa reescrita — sem ela o kernel lê "0 bytes
de RAM" e nunca sai da inicialização de zonas de memória.

## `initramfs.cpio.gz` / `init`

Reaproveitados de `testdata/` (versatilepb, B4.1.5) sem nenhuma mudança — `busybox-armv5l`
(ARM mode, não Thumb) roda em qualquer core ARMv5TE+ por compatibilidade retroativa, incluindo
o ARM1176JZF-S/ARMv6K desta máquina (confirmado pela spec da task, "reaproveitar o do
versatilepb, se o `busybox-armv5l` rodar" — não foi necessário baixar uma variante
`busybox-armv6l` separada). `sha256sum` de `initramfs.cpio.gz` nesta cópia:
`6932f8092eccfbbe0a1183c0b00ae0c37c4424cc235e74399d9e66885ac4e112` (idêntico ao de
`testdata/initramfs.cpio.gz` — mesmo arquivo, copiado).

## Mapa de memória / boot usado por este repo

`Bcm2835Machine` carrega `kernel.img` em `0x00010000` (protocolo direto de boot do QEMU —
`KERNEL_LOAD_ADDR` de `hw/arm/boot.c` — não os `0x8000` do bootloader proprietário real, que
este repo não tem) e `initramfs.cpio.gz` em `0x08000000` (RAM de 256MiB: metade da RAM, mesma
fórmula do `hw/arm/boot.c` usada pelo `versatilepb`), o `.dtb` patcheado
(`FdtPatcher.withBootargs`+`withMemorySize`) alinhado a 4KiB logo após o initramfs, e entra em
`0x00010000` com `r0=0`, `r1=3138` (`0x0C42`, `MACH_TYPE_BCM2708` — ignorado por kernels DT
modernos, passado por segurança), `r2=<endereço do dtb>`.

## Oráculo de validação

`qemu-system-arm -M raspi1ap -cpu arm1176 -kernel testdata/raspi1/kernel.img -dtb
testdata/raspi1/bcm2708-rpi-b.dtb -m 512 -append "console=ttyAMA0,115200 earlycon" -serial
stdio -display none -no-reboot` — usado nesta sessão para confirmar que o `kernel.img`+`.dtb`
reais bootam até shell completo (`raspi1ap` modela BCM2835 igual ao Pi 1B), e para decidir o
marcador correto de M1 (ver achado acima). **Não** é o mesmo hardware modelado por
`Bcm2835Machine` (QEMU implementa dezenas de periféricos fora do "Inclui" desta task — GPU/DMA/
USB/SD/clock manager CPRMAN — todos servidos por `OpenBus` aqui), só a mesma combinação de
kernel/DTB/CPU base.
