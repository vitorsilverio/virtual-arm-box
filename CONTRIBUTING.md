# Como contribuir

Este é um projeto pessoal, mas issues e pull requests são bem-vindos.

## Antes de abrir um PR

- Abra uma issue descrevendo o problema/ideia primeiro (use os templates de
  [bug](.github/ISSUE_TEMPLATE/bug.yml) ou [feature](.github/ISSUE_TEMPLATE/feature.yml)) —
  evita trabalho duplicado ou um PR que não se encaixa na direção do projeto.
- Compile e teste com **JBR 25** (a JDK do IntelliJ), não a JDK do sistema:

  ```bash
  mvn -o test
  ```

- Toda mudança de comportamento vem com teste automatizado cobrindo o caso novo.
- A CPU ARM/MMU vem do [`arm-jitter`](https://github.com/vitorsilverio/arm-jitter) — se
  sua mudança exigir uma feature nova da CPU em si (nova instrução, novo registrador de
  sistema), ela provavelmente pertence lá, não aqui.
- Mantenha o estilo do código existente; não introduza dependências novas sem discutir
  antes na issue.

## Dúvidas

Abra uma issue ou veja a seção de contato no [README](README.md).
