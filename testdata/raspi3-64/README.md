# testdata/raspi3-64/ — kernel + DTB AArch64 versionados (F11)

Mesmo espírito de `testdata/raspi1/README.md` (F3): binários REAIS, baixados de fontes
públicas, URL e commit exatos documentados aqui, versionados no repo para reprodutibilidade.

## `initramfs.cpio.gz` — **SINTÉTICO, não baixado** (mesmo bloqueio de B6.2 aceite #2/B4.0.3
## item 3: busybox estático `aarch64` real indisponível — `busybox.net` só publica `armv8l`,
## que é ARM 32-bit, ISA errada; devkitA64 instalado é bare-metal, sem libc/toolchain
## `aarch64-linux-*` para compilar um binário estático real)

Arquivo `cpio` (formato `newc`) contendo só o registro `TRAILER!!!` (arquivo vazio, 124 bytes),
comprimido com `gzip`. **Não é um initramfs funcional** — não tem `/init` nem `/bin/sh` — mas
não precisa ser: os marcos de boot desta task (`EARLYCON_BANNER`/`FREEING_KERNEL_MEMORY`, ver
`Raspi364BootTest`) acontecem ANTES de `populate_rootfs` tentar montar/executar o initramfs; um
`cpio` inválido ou vazio faz o kernel real logar um aviso e seguir sem travar (só falharia mais
tarde, em `run_init_process`/"No working init found" — depois dos marcos que este teste
verifica). Quando um busybox estático `aarch64` real ficar disponível (mesmo ambiente que
destrava B6.2 aceite #2), substituir este arquivo por um initramfs de verdade (mesmo padrão de
`testdata/raspi1/initramfs.cpio.gz`) para poder perseguir M3 (shell interativo).

`sha256sum`: `bf2f28f9d3f267d1f4c32e685f6646db5a60ef962136508105f69e63ba9d7061`.

## `kernel8.img`

Kernel Linux **AArch64** pré-compilado oficial do Raspberry Pi Foundation (Pi 2 v1.2/3/3+),
do MESMO repositório e MESMO commit já fixado por `testdata/raspi1/README.md` (F3, branch
`stable`, 2026-08-15):

```
https://github.com/raspberrypi/firmware/raw/12eeaa12865869b07db760f4bbb7507ec6f1976c/boot/kernel8.img
```

Commit fixado: `12eeaa12865869b07db760f4bbb7507ec6f1976c` ("Update kernel to 6.18.33").
`sha256sum`: `80d7b1589d51f2ed3478a7729d9394fe5518819f243cef53ff36a6e0093a9876`. `file` confirma:
`Linux kernel ARM64 boot executable Image, little-endian, 4K pages`.

**Armadilha real desta sessão (achado, não do enunciado)**: `curl` (mesmo com `--compressed`)
salvou o arquivo com magic `1F 8B 08 08` — um stream **gzip** cru com o nome original
embutido (`"Image\0"`), NÃO o binário `Image` do protocolo de boot arm64. Isso não é uma
característica do arquivo real no repositório `raspberrypi/firmware` (o `kernel8.img` real
NÃO é gzip) — é o proxy `raw.githubusercontent.com` devolvendo `Content-Encoding: gzip` que
o `curl` desta máquina não descomprime automaticamente (nem com `--compressed`, possivelmente
por causa do redirect `github.com/.../raw/...` → `raw.githubusercontent.com/...`). Corrigido
com `gunzip -k -c kernel8.img.gz > kernel8.img` — o binário resultante tem o magic real do
protocolo de boot arm64 confirmado por inspeção de bytes: `"ARM\x64"` no deslocamento `0x38`
(`arch/arm64/kernel/image-vars.h`/`Documentation/arch/arm64/booting.rst`: cabeçalho de 64
bytes, `code0`/`code1`(8B) → `text_offset`(u64 LE, offset 8) → `image_size`(u64, offset 16) →
`flags`(u64, offset 24) → `res2`/`res3`/`res4`(24B) → `magic`(4B, offset 56=`0x38`) → `res5`
(4B)). **Se uma sessão futura rebaixar este arquivo, CONFERIR o magic em `0x38` antes de
assumir que o download deu certo** — o sintoma de "gzip não descomprimido" é silencioso (o
arquivo `.img` existe, tem tamanho grande, `sha256sum` roda sem erro; só um `file`/inspeção de
bytes revela o problema).

## `bcm2710-rpi-3-b.dtb`

Device Tree Blob do Raspberry Pi 3 Model B, mesmo commit acima:

```
https://github.com/raspberrypi/firmware/raw/12eeaa12865869b07db760f4bbb7507ec6f1976c/boot/bcm2710-rpi-3-b.dtb
```

`sha256sum`: `cd922193672e391b6194d6d94e34ffab6fc154205375fcfb362cad191f7f8aae`. Cabeçalho FDT
confirmado por inspeção manual de bytes (sem `dtc`/`fdtdump` disponíveis nesta máquina, mesmo
bloqueio de toolchain já registrado por `testdata/raspi1/README.md`): magic `0xD00DFEED`,
`totalsize=0x000087ab=34731` bytes — bate exatamente com o tamanho do arquivo baixado (nenhum
truncamento). `strings` sobre o blob confirma os nós/`compatible` esperados pela spec F11:

- `cpu@0`, `cpu@1`, `cpu@2`, `cpu@3` (quad-core, confirma a necessidade de podar `cpu@1..3` —
  ver `FdtPatcher#withNodeRemoved`, item 2 do "Inclui" da task).
- `brcm,bcm2837` (compatible da placa) — **não** `brcm,bcm2836` (esse é o SoC do Pi 2, silício
  irmão mas revisão diferente; o Pi 3 real usa BCM2837, confirmando a nota da task "BCM2837 é
  peça de silício compatível" com o BCM2836/2835).
- `brcm,bcm2836-armctrl-ic` — o MESMO controlador de interrupção legado do BCM2835/raspi1 (F3),
  confirma que `device/bcm2835/Bcm2835Ic` pode ser reutilizado sem alteração.
- `brcm,bcm2836-l1-intc` — o controlador local por-núcleo esperado pela spec.
- `arm,armv7-timer` — **achado NOVO, não coberto pela F3**: o timer ARM genérico (registrado
  via `MRC`/`MRS` de sistema — `CNTFRQ_EL0`/`CNTPCT_EL0`/`CNTP_CTL_EL0`/`CNTP_TVAL_EL0` em
  AArch64), consumido pelo `l1-intc` via PPI. Ver a nota de bloqueio arquitetural no relatório
  da sessão: o `arm-jitter` NÃO modela nenhum registrador de sistema de timer genérico hoje
  (`Aarch64SystemRegisterId` só tem os de MMU/exceção de B6.6.1-B6.6.4) — item pendente,
  registrado, não implementado nesta sessão (fora do "Inclui": não é bug, é feature nova).
- `brcm,bcm2836-smp` — método de habilitação de SMP via mailbox (endereço de spin, escrito
  pelo host em `0x3F00_008C`+offset por núcleo) — **não** PSCI, confirma a decisão de escopo da
  task ("Sem PSCI").
- `arm,cortex-a53`/`arm,cortex-a53-pmu`/`brcm,bcm2837-thermal`/`brcm,bcm2836-vchiq` — fora do
  "Inclui" desta task (PMU, sensor térmico, VCHIQ), ficam em `OpenBus` como qualquer outro nó
  não implementado (mesma disciplina da F3).

## Oráculo

Nenhum oráculo QEMU aarch64 foi usado nesta sessão (diferente da F3, que tinha
`qemu-system-arm` instalado) — não há `qemu-system-aarch64` disponível nesta máquina. A
validação de proveniência desta sessão ficou restrita a inspeção de bytes/cabeçalho (magic FDT,
magic arm64 Image, `strings` sobre o `.dtb`) mais o achado documentado acima.
