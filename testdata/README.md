# testdata/ — kernel + initramfs versionados (B4.1.5)

Reprodutibilidade primeiro: os binários abaixo são reais (baixados de fontes públicas,
URLs e versões exatas documentadas aqui) e versionados neste repo, como o busybox do
`armbox` — sem eles o teste de boot-to-shell não teria como rodar em CI/outra máquina.

## Por que binários pré-compilados (não buildados da fonte)

Esta máquina não tem um cross-toolchain `arm-linux-gnueabi*` (musl ou glibc) capaz de
compilar um kernel Linux real ou um `busybox` ELF `arm-linux` a partir da fonte — mesmo
bloqueio já documentado em `arm-jitter/tasks/README.md` para B4.0.3 (item 3, busybox
Thumb-2) e B6.2 (aceite #2, busybox aarch64): `devkitARM`/`devkitA64` instalados são
bare-metal (`*-none-eabi`, sem libc de userspace Linux) e não há WSL configurado. Ver a
seção "Blocked / gap real" no relatório da task para o que destravaria compilar da fonte.

Dado esse bloqueio, os artefatos abaixo são binários REAIS, prontos, de fontes públicas
confiáveis — não fabricados/sintéticos — e cobrem exatamente o hospedeiro `versatilepb`
que a RFC-SOFTMMU pede.

## `vmlinuz-3.2.0-4-versatile`

Kernel ARM Linux 3.2.0 (build oficial Debian, pacote `linux-image-3.2.0-4-versatile`),
baixado do arquivo histórico do Debian:

```
http://archive.debian.org/debian/dists/wheezy/main/installer-armel/current/images/versatile/netboot/vmlinuz-3.2.0-4-versatile
```

`file` confirma: `Linux kernel ARM boot executable zImage (little-endian)`.

**Desvio documentado frente ao aceite literal da task** ("kernel mainline
`versatile_defconfig`"): este é um build **Debian** de um kernel 3.2 real para a placa
`versatile` (mesmo board file `arch/arm/mach-versatile`, mesmo protocolo de boot ATAGs,
mesmo ID de máquina `387`/`0x183` que o QEMU `-M versatilepb` usa) — não um zImage
compilado por este agente a partir do `versatile_defconfig` do mainline atual, porque não
há toolchain para produzir esse binário nesta máquina (ver acima). É um kernel real,
funcional, para a placa certa — só não é "self-built from vanilla mainline source".
Vale notar também que o `arch/arm/mach-versatile` **atual** do mainline (2026) já
abandonou o boot via ATAGs/board-file em favor de Device Tree exclusivamente
(`DT_MACHINE_START` em `mach-versatile/versatile.c`) — um `versatile_defconfig` recém
buildado exigiria DTB, não ATAGs. O kernel 3.2 da Debian é anterior a essa migração e
aceita ATAGs puro, que é o que este repo monta (`AtagsBuilder`) — mais simples e
suficiente para o aceite real (boot até shell), mas outro desvio frente à letra da task.

Se uma sessão futura tiver acesso a um `arm-linux-gnueabihf-*` cross-toolchain real (ou a
WSL configurado com uma distro Debian/Ubuntu armhf), o caminho para fechar 100% o aceite
literal é: `make versatile_defconfig && make zImage versatile-pb.dtb` no kernel mainline
atual, e portar `AtagsBuilder`/o loader deste repo para montar um FDT (device tree) em vez
de ATAGs.

## `busybox-armv5l`

Binário estático (musl, `EABI5`, ARM — não Thumb, não AArch64) do busybox 1.31.0, dos
binários pré-compilados oficiais do projeto busybox:

```
https://busybox.net/downloads/binaries/1.31.0-defconfig-multiarch-musl/busybox-armv5l
```

Este é o achado que destrava B4.1.5 onde B4.0.3/B6.2 ficaram parciais: aqueles precisavam
de um binário Thumb-2 ou AArch64 (que o busybox.net não publica), mas o `versatile_defconfig`
roda em modo ARM puro (ARMv6K, sem Thumb-2 no kernel/initramfs) — e o busybox.net publica
justamente essa variante (`armv5l`, ARM mode, compatível com qualquer core >= ARMv5TE,
incluindo o ARM1176/ARMv6K do preset `ArmArchitecture.ARMV6K` do arm-jitter).

## `init` + `initramfs.cpio.gz`

`init` é o script (shebang `#!/bin/busybox sh`) executado como `rdinit=/init` (PID 1):
instala os applets do busybox em `/bin` (`busybox --install -s /bin`), monta `/proc` e
`/sys`, e cai num shell interativo (`exec /bin/sh`).

`initramfs.cpio.gz` é gerado por `make_initramfs.py` (formato `newc` do cpio escrito à mão,
sem depender de um binário `cpio` — indisponível nesta máquina Windows/MSYS2) a partir de
`busybox-armv5l` + `init`, determinístico (ino/mtime fixos) para o artefato ser
reproduzível byte-a-byte. Para reconstruir depois de editar `init`:

```
python testdata/make_initramfs.py
gzip -kf testdata/initramfs.cpio   # o kernel só lê o .gz; o .cpio intermediário não é versionado
rm testdata/initramfs.cpio
```

## Mapa de memória / boot usado por este repo

`VersatilePbMachine` carrega `vmlinuz-3.2.0-4-versatile` em `0x00010000` e
`initramfs.cpio.gz` em `0x04000000` (RAM de 128MiB: metade da RAM, mesma fórmula do
`hw/arm/boot.c` do QEMU para hospedeiros com menos de 256MiB), monta uma lista de ATAGs em
`0x00000100` (`ATAG_CORE`, `ATAG_MEM` tamanho=128MiB, `ATAG_INITRD2`, `ATAG_CMDLINE`
`"console=ttyAMA0 root=/dev/ram rdinit=/init"`, `ATAG_NONE`) e entra em `0x00010000` com
`r0=0`, `r1=387` (`0x183`, ID de máquina `versatile_pb` — `arch/arm/tools/mach-types` do
kernel), `r2=0x100`.
