# linuxbox

Hospedeiro "full-system" para o arm-jitter (tarefa B4.1.5 do épico B4.1/MMU-softmmu,
RFC-SOFTMMU): emula uma placa `versatilepb`-like (`-M versatilepb` do QEMU) o suficiente para
levar um kernel Linux real até um **shell `busybox` interativo** — alcançado, nos backends
interpretado E ASM/JIT (`VersatilePbBootTest`).

Periféricos: RAM 128MiB (`PagedAddressSpace`), PL011 UART0 (console), SP804 (dois
temporizadores duplos), PL190 (VIC primário). MMU via `TranslatingAddressSpace` +
`Cp15VmsaCoprocessor` do arm-jitter (B4.1.1-B4.1.4).

CPU do guest: **ARM926EJ-S com a VFP9-S opcional presente** (`ArmArchitecture.ARMV5TE` +
`ArmFeature.VFPV2`, o mesmo que o `-cpu arm926` do QEMU expõe). A RFC pedia ARM1176/ARMv6K; o
desvio é forçado pelo kernel real disponível e está documentado no Javadoc de
`VersatilePbMachine` e de `device/VersatileCp15Extras`.

## Rodar

```
mvn -o package
java -cp target/classes:<arm-jitter.jar> dev.vitorsilverio.linuxbox.Main \
    [--interp|--check] [--cycles=N] testdata/vmlinuz-3.2.0-4-versatile testdata/initramfs.cpio.gz
```

O `stdin` do host é drenado (sem bloquear) para o UART0 do guest, então o shell responde a
comandos digitados. Sem argumento de backend o default é JIT.

## Ver também

`tasks/README.md`/`docs/RFC-SOFTMMU.md` no repo `arm-jitter` para o desenho completo, e
`testdata/README.md` deste repo para a proveniência exata do kernel/initramfs versionados
(binários reais, reprodutibilidade documentada — sem toolchain `arm-linux-*` disponível nesta
máquina para compilar da fonte, ver a seção "Blocked" lá).

## Licença

BSD 3-Clause — ver [LICENSE](LICENSE).

Os binários de terceiros usados em testes e execução (BIOS, firmware, ROMs, kernels,
`busybox`) **não** são cobertos por esta licença e não são redistribuídos por este projeto
salvo quando a licença original permitir; ver o `README.md` do diretório correspondente.
