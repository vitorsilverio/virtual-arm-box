# linuxbox

Hospedeiro "full-system" para o arm-jitter (tarefa B4.1.5 do épico B4.1/MMU-softmmu,
RFC-SOFTMMU): emula uma placa `versatilepb`-like (ARM1176/ARMv6K, `-M versatilepb -cpu arm1176`
do QEMU) o suficiente para levar um kernel Linux real até um shell `busybox` interativo.

Periféricos: RAM 128MiB (`PagedAddressSpace`), PL011 UART0 (console), SP804 (dois
temporizadores duplos), PL190 (VIC primário). MMU via `TranslatingAddressSpace` +
`Cp15VmsaCoprocessor` do arm-jitter (B4.1.1-B4.1.4, já prontos).

Ver `tasks/README.md`/`docs/RFC-SOFTMMU.md` no repo `arm-jitter` para o desenho completo, e
`testdata/README.md` deste repo para a proveniência exata do kernel/initramfs versionados
(binários reais, reprodutibilidade documentada — sem toolchain `arm-linux-*` disponível nesta
máquina para compilar da fonte, ver a seção "Blocked" lá).
