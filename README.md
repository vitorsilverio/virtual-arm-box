# virtual-arm-box

Emulador de **máquina ARM completa** (CPU + MMU + periféricos + boot) sobre a biblioteca
`arm-jitter`; irmão do `armbox` (user-mode) e dos emuladores `gbaemu`/`ndsemu`.

Periféricos da `versatilepb`: RAM 128MiB (`PagedAddressSpace`), PL011 UART0 (console), SP804
(dois temporizadores duplos), PL190 (VIC primário). MMU via `TranslatingAddressSpace` +
`Cp15VmsaCoprocessor` do arm-jitter (B4.1.1-B4.1.4).

## Máquinas

| Máquina | `--machine=` | CPU do guest | Estado |
|---------|--------------|--------------|--------|
| ARM VersatilePB | `versatilepb` | ARM926EJ-S (ARMv5TE + VFPv2) | ✅ boota Linux real até shell `busybox` interativo (JIT e interpretado) |
| Raspberry Pi 1 / Zero | `raspi1` | ARM1176JZF-S (ARMv6K + VFPv2) | 🔜 task F3 |

Disco virtual (`raw`/QCOW2) é a task F10 e ainda não existe: hoje a raiz vem de `initramfs`.

## Rodar

```
mvn -o package
java -cp target/classes:<arm-jitter.jar> dev.vitorsilverio.virtualarmbox.Main \
    --machine=versatilepb [--interp|--check] [--cycles=N] \
    testdata/vmlinuz-3.2.0-4-versatile testdata/initramfs.cpio.gz
```

O `stdin` do host é drenado (sem bloquear) para o UART0 do guest, então o shell responde a
comandos digitados. Sem argumento de backend o default é JIT. Sem `--machine=`, o default é
`versatilepb`.

## Não é objetivo

Esta é uma máquina **ARM**; rodar Windows/Android exige a máquina AArch64 `virt` com UEFI, que
depende de B6.6.6 fechar no arm-jitter. **macOS não é alvo** (Apple Silicon não é documentado e
o boot é acorrentado ao hardware da Apple). Ver `ROADMAP.md`.

## Ver também

`tasks/README.md`/`docs/RFC-SOFTMMU.md` no repo `arm-jitter` para o desenho completo, e
`testdata/README.md` deste repo para a proveniência exata do kernel/initramfs versionados
(binários reais, reprodutibilidade documentada — sem toolchain `arm-linux-*` disponível nesta
máquina para compilar da fonte, ver a seção "Blocked" lá).

## Como contribuir

Issues e pull requests são bem-vindos — ver [CONTRIBUTING.md](CONTRIBUTING.md).

## Autor e contato

Feito por [Vitor Silvério Rodrigues](https://vitorsilverio.dev/) — blog/currículo com mais
detalhes sobre este e outros projetos. Contato: vitor.silverio.rodrigues@gmail.com ou uma
[issue](https://github.com/vitorsilverio/virtual-arm-box/issues) neste repositório.

## Licença

BSD 3-Clause — ver [LICENSE](LICENSE).

Os binários de terceiros usados em testes e execução (BIOS, firmware, ROMs, kernels,
`busybox`) **não** são cobertos por esta licença e não são redistribuídos por este projeto
salvo quando a licença original permitir; ver o `README.md` do diretório correspondente.
