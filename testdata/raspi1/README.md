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

**Atualizado na sessão 2/3**: `kernel.img` não é mais carregado cru. A sessão 1 media que
descomprimir os ~7,7MB do `kernel.img` byte a byte DENTRO do guest (o `inflate()` do próprio
stub `head.S`/`misc.c`) custava ~750 milhões de ciclos — caro demais para terminar num
orçamento de sessão/CI razoável. `Bcm2835Machine` agora descomprime no HOST
(`boot.ZImageDecompressor`, achado: o payload é um stream gzip padrão embutido no zImage,
localizável pelo magic `1F 8B 08`, mesma técnica do `scripts/extract-vmlinux` do kernel Linux)
e carrega a imagem JÁ DESCOMPRIMIDA em `0x00008000` (`ZImageDecompressor.TEXT_OFFSET` —
o `AUTO_ZRELADDR` que o stub calcularia em tempo de execução para um zImage carregado bem
abaixo de 128MiB, é também o endereço de boot clássico do Raspberry Pi real), com o PC do core
apontando direto para o `stext` do kernel. `initramfs.cpio.gz` continua em `0x08000000` (RAM de
256MiB: metade da RAM, mesma fórmula do `hw/arm/boot.c` usada pelo `versatilepb`), o `.dtb`
patcheado (`FdtPatcher.withBootargs`+`withMemorySize`) alinhado a 4KiB logo após o initramfs.
Entrada em `0x00008000` com `r0=0`, `r1=3138` (`0x0C42`, `MACH_TYPE_BCM2708` — ignorado por
kernels DT modernos, passado por segurança), `r2=<endereço do dtb>` — o resto do protocolo de
entrada não muda.

## Achados de CP15 corrigidos no `arm-jitter` (sessão 2/3)

Destravar a descompressão revelou que o boot avançava muito mais fundo no kernel real — e
esbarrava em DOIS registradores CP15 do esquema ARMv6+ que o `Cp15VmsaCoprocessor`/
`Bcm2835Cp15Extras` ainda não conheciam (primeira validação de sistema real do
`ARM11_MPCORE`/ARMv6K neste repo). Os dois causavam UNDEFINED tão cedo no boot que os vetores
de exceção ainda não tinham sido copiados por `early_trap_init()`, cascateando num laço
infinito de `PREFETCH_ABORT` (a busca da PRÓPRIA rotina de vetor também falhava):

1. `MCR p15,0,Rt,c13,c0,3` (`TPIDRURO`, ponteiro de TLS) — corrigido no `arm-jitter`
   (`Cp15VmsaCoprocessor`): `c13,c0,{0,2,3,4}` (FCSEIDR/TPIDRURW/TPIDRURO/TPIDRPRW) viraram
   armazenamento simples, sem efeito colateral.
2. `MRC p15,0,Rt,c0,c1,4` (`ID_MMFR0`, usado por `build_mem_type_table()`) e depois `c0,c3,4`
   (sub-registrador do esquema CPUID sem nome nesta revisão da arquitetura) — corrigido em
   `Bcm2835Cp15Extras`: a arquitetura ARM GARANTE que ler um sub-registrador de ID não
   alocado/reservado devolve um valor UNKNOWN (aqui `0`), NUNCA lança UNDEFINED (mecanismo de
   compatibilidade futura do próprio esquema CPUID) — em vez de listar `CRm` um a um, a classe
   agora reivindica o esquema `c0`/`opcode1=0` inteiro.

Depois dos dois fixes, o boot avança centenas de milhares de ciclos a mais (dezenas de funções
de kernel distintas visitadas, confirmado por rastreamento instrução-a-instrução) e esbarra num
LIMITE DELIBERADO e já documentado do `arm-jitter` (não um bug): `CPSR.E=1`/acesso a dado
big-endian (`SETEND BE`, ARMv6) não é suportado (`IrExecutionSupport.checkLittleEndianData`,
task `B1.5` do `arm-jitter`, MVP explicitamente só little-endian). O kernel ARMv6K real toca
isso bem cedo no boot (antes de `setup_arch()`, console ainda vazio). Ver Javadoc de
`Raspi1BootTest` para o detalhe completo — M1 ainda não fecha por causa deste limite, que é
funcionalidade nova no `arm-jitter` (não um bug desta task) e fica para decisão de sessão
futura.

## Oráculo de validação

`qemu-system-arm -M raspi1ap -cpu arm1176 -kernel testdata/raspi1/kernel.img -dtb
testdata/raspi1/bcm2708-rpi-b.dtb -m 512 -append "console=ttyAMA0,115200 earlycon" -serial
stdio -display none -no-reboot` — usado nesta sessão para confirmar que o `kernel.img`+`.dtb`
reais bootam até shell completo (`raspi1ap` modela BCM2835 igual ao Pi 1B), e para decidir o
marcador correto de M1 (ver achado acima). **Não** é o mesmo hardware modelado por
`Bcm2835Machine` (QEMU implementa dezenas de periféricos fora do "Inclui" desta task — GPU/DMA/
USB/SD/clock manager CPRMAN — todos servidos por `OpenBus` aqui), só a mesma combinação de
kernel/DTB/CPU base.
