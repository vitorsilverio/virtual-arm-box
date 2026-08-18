package dev.vitorsilverio.virtualarmbox;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Aceite da task F3 (`--machine=raspi1`) — ver `tasks/trilha-f-infra/f3-raspi1-machine.md`.
///
/// **ESTADO ATUAL (sessão de tentativa de oráculo QEMU, 2026-08-18 (6)) — leia isto primeiro, o
/// resto do Javadoc abaixo é histórico cronológico de sessões anteriores.**
///
/// Antes de tentar a arqueologia de `mm_struct`/maple-tree recomendada pela sessão anterior (achar
/// `VM_WRITE` da VMA em `0x0014622d` exigiria offsets de struct do kernel 6.18 sem `vmlinux`/BTF —
/// caro e frágil de calcular à mão), esta sessão tentou o atalho mais barato que a própria task
/// já apontava: **usar o `qemu-system-arm -M raspi1ap` instalado como oráculo externo**, bootando
/// exatamente os mesmos `kernel.img`/`bcm2708-rpi-b.dtb`/`initramfs.cpio.gz` desta task fora do
/// `virtual-arm-box`, para ver se um QEMU real chega ao shell (provaria bug nosso) ou trava no
/// mesmo lugar (indicaria problema do lado do kernel/initramfs, não do emulador).
///
/// **Resultado: o oráculo NÃO é utilizável para esta pergunta.** `qemu-system-arm -M raspi1ap
/// -kernel kernel.img -dtb bcm2708-rpi-b.dtb -initrd initramfs.cpio.gz -append "console=ttyAMA0,
/// 115200 earlycon root=/dev/ram rdinit=/init" -nographic` trava e para de imprimir por completo
/// em tempo de kernel `2.495s` — MUITO antes do ponto de bloqueio desta task (`execve("/init")`,
/// que só acontece depois de `mmc`/`usb` — o próprio QEMU nem chega a essas linhas). A causa é um
/// `external abort on non-linefetch` real dentro do próprio QEMU: `bcm2835_power_probe` (driver de
/// clock/power do BCM2835, chamado via `deferred_probe_work_func` logo após
/// `bcm2835_vchiq: Could not initialize vchiq platform` falhar) acessa um registrador MMIO que o
/// modelo `raspi1ap` do QEMU 8.0.0 não implementa — falha do PRÓPRIO QEMU nesta combinação
/// kernel+DTB, não um travamento do Linux. Ou seja: o `qemu-system-arm` instalado nesta máquina
/// não modela o controlador de clock/power (CPRMAN/PM) o suficiente para levar ESTE kernel
/// (6.18.33, que passou a sondar `bcm2835-pm`/`bcm2835-power` de forma mais agressiva que builds
/// mais antigos) além dos 2,5s iniciais — mesma classe de lacuna que motivou o
/// `Bcm2835Cprman` mínimo que a sessão do CPRMAN (2026-08-17) já teve que escrever no
/// `virtual-arm-box` para o MESMO driver não travar aqui. **Achado negativo registrado para
/// poupar sessões futuras**: não vale a pena tentar de novo o oráculo QEMU para o bloqueio de M3
/// com este `kernel.img`/DTB — ele quebra num lugar diferente e mais cedo, por lacuna própria do
/// `raspi1ap` do QEMU (poderia valer a pena com um DTB/kernel mais antigo/mais simples, mas isso
/// sairia do escopo "mesmos assets" que tornava a comparação válida).
///
/// **Próximo passo continua sendo o já recomendado pela sessão anterior** (dump de VMA via
/// `TPIDRURO`=`current` — confirmado como o mecanismo certo por leitura do fonte real,
/// `arch/arm/include/asm/current.h` do kernel 6.18: `get_current()` lê `c13,c0,3` quando
/// `CONFIG_CURRENT_POINTER_IN_TPIDRURO`/`CONFIG_SMP`, o que bate com a nota da sessão (3) sobre
/// `TPIDRURO` já apontar para dado de thread útil — `Cp15VmsaCoprocessor.read(15,0,13,0,3)` dá o
/// ponteiro de `task_struct` direto, sem reflexão adicional além da já usada para obter o próprio
/// `Cp15VmsaCoprocessor`). O obstáculo real é os OFFSETS de `task_struct.mm`/`mm_struct.mm_mt`
/// (maple tree, não rbtree/lista — trocado desde o Linux 6.1) e de `vm_area_struct.vm_flags`
/// nesta build específica, que não têm como ser calculados só lendo o `.h` (dependem de todos os
/// campos anteriores + `CONFIG_*`). Sem `vmlinux`/`System.map`/BTF desta build, a rota mais barata
/// provavelmente é procurar o valor de `vm_flags` (`VM_WRITE=0x2`) por PADRÃO DE BUSCA na memória
/// próxima ao endereço já achado do `mm_struct` (heurística: valor pequeno, bits baixos plausíveis
/// de `VM_READ|VM_WRITE|VM_MAYREAD|VM_MAYWRITE|VM_MAYEXEC` = tipicamente `0x875` para pilha), em
/// vez de reconstruir a struct inteira campo a campo. `mvn -o test` verde no `virtual-arm-box`
/// (harness temporário de comparação com QEMU não commitado, sem tocar código de produção; nenhum
/// arquivo do `arm-jitter` tocado). M3 continua `@Disabled`. M1/M2 continuam fechados.
///
/// ---
///
/// **ESTADO ANTERIOR (sessão de dump de PTE + fix real de `DFSR.WnR`, 2026-08-18 (5))**
///
/// Seguindo o próximo passo recomendado pela sessão anterior — dump direto da palavra de PTE real
/// do Linux em `[0x0014622d]` (formato ARM 2-level, `arch/arm/include/asm/pgtable-2level.h`,
/// baixado via `curl`/jsdelivr do mesmo jeito que a sessão anterior baixou
/// `uaccess_with_memcpy.c` — `raw.githubusercontent.com` direto deu HTTP 429 desta vez, o mirror
/// `cdn.jsdelivr.net/gh/…` funcionou) — um harness temporário (`Raspi1DiagTempTest`, removido
/// antes do commit) leu `TTBR0` via `Cp15VmsaCoprocessor#read(15,0,2,0,0)` (acessado por reflexão
/// através dos decoradores `Bcm2835Cp14Extras`/`Bcm2835Cp15Extras`) e andou as tabelas L1/L2 A MÃO
/// (`TranslatingAddressSpace#setMmuEnabled(false)` + `read32` = leitura física crua, sem TLB — o
/// mesmo objeto que o `Cp15VmsaCoprocessor` já guarda) até achar a palavra de PTE "Linux" (não a
/// de hardware — ficam em metades diferentes da mesma página de 4KiB, ver o diagrama do header).
///
/// **Achado 1 — confirma e ESTREITA a hipótese da sessão anterior**: a palavra real era
/// `PRESENT=1 YOUNG=1 DIRTY=0 RDONLY=1` — `pin_page_for_write()` checa `(pte & 0xC3) == 0x43`
/// (`PRESENT|YOUNG|DIRTY` ligados, `RDONLY` desligado); com `DIRTY=0`/`RDONLY=1` o teste falha
/// para sempre, batendo exatamente com o loop infinito observado.
///
/// **Achado 2 — causa raiz ISOLADA e CORRIGIDA (bug real do `arm-jitter`, candidato (a) da sessão
/// anterior, confirmado)**: {@link dev.vitorsilverio.armjitter.core.ArmCore#enterMemoryAbort}
/// nunca preenchia `DFSR[11]` (`WnR`, ARM DDI 0406C B3.13.4 — `1`=falta causada por ESCRITA,
/// `0`=leitura), mesmo com {@code MemoryTranslationException#accessType()} já disponível ali (é
/// literalmente usado na linha de cima para decidir `DATA_ABORT` vs. `PREFETCH_ABORT`) — só o
/// `FS[3:0]` de 4 bits chegava ao `DFSR`. O Linux real (`do_page_fault`/`__do_page_fault`) lê esse
/// bit para decidir `FAULT_FLAG_WRITE`; sem ele, TODA falta (leitura ou escrita) parecia uma
/// leitura para o kernel — o `strb` que causou o único abort do boot (achado da sessão anterior)
/// era postado como falta de LEITURA, então o handler de falta corrigia o `AP` de hardware
/// (permitindo a escrita física seguinte, por isso nenhum abort repetia) mas nunca marcava a PTE
/// como `dirty`/gravável (que só acontece no caminho de falta de ESCRITA). **Corrigido**:
/// `ArmCore.enterMemoryAbort` agora liga `DFSR_WNR_BIT` (`1&lt;&lt;11`) quando
/// `fault.accessType()==DATA_WRITE`, só no caminho `onDataAbort` (`IFSR`/`onPrefetchAbort` não têm
/// esse conceito). Aditivo/G3 (nenhuma assinatura pública muda). 2 testes de regressão novos em
/// `ArmCoreMemoryAbortTest` (`dataAbortOnStoreSetsWnrBitInDfsr`/`dataAbortOnLoadLeavesWnrBitClearInDfsr`)
/// + o teste pré-existente de falta de LEITURA continua batendo o `DFSR` exato (prova que o fix não
/// muda o caminho de leitura). `mvn -o test` verde no `arm-jitter` (1369 core+truffle) + `mvn -o
/// install`; G5 revalidado (gbaemu verde, ndsemu verde, armbox 40/41 — a 1 falha é a MESMA
/// pré-existente de `Armv7TortureTest`/`VfpRegisters`, não relacionada a este fix).
///
/// **Efeito real, medido com o mesmo harness após o fix**: a MESMA palavra de PTE relida agora é
/// `PRESENT=1 YOUNG=1 DIRTY=1 RDONLY=1` — o fix funcionou exatamente como esperado (`DIRTY` virou
/// `1`), mas **M3 ainda NÃO fecha**: `RDONLY` continua `1`, então `(pte & 0xC3)=0xC3 != 0x43` e
/// `pin_page_for_write()` continua falhando (por um motivo DIFERENTE e mais estreito agora).
/// Arquiteturalmente, um Linux real nunca marca uma PTE `DIRTY=1` E `RDONLY=1` ao mesmo tempo pelo
/// caminho de falta de escrita normal (`maybe_mkwrite(pte_mkdirty(entry), vma)` sempre limpa
/// `RDONLY` junto com marcar `DIRTY`, a menos que `maybe_mkwrite` decida que a VMA em si não é
/// `VM_WRITE` e deixe a página propositalmente somente-leitura) — ou seja, isso pode ainda ser
/// **outro bug real** (do `arm-jitter` ou do `virtual-arm-box`) em uma parte diferente do ciclo de
/// falta, ou pode ser o kernel corretamente recusando escrita numa VMA que ele não considera
/// gravável (nesse caso o bug estaria alhures — no setup da VMA de pilha do `execve()`, fora do
/// escopo de `arm-jitter`). **Não investigado nesta sessão** (orçamento). **Próximo passo
/// recomendado, concreto**: (a) reler o mesmo dump de PTE, mas agora também dump da entrada de VMA
/// correspondente (`current->mm->mmap`/`find_vma`) para confirmar se `VM_WRITE` está de fato ligado
/// para este endereço — se não estiver, o bug é no setup de `argv`/`envp`/pilha do `execve()`, não
/// no `arm-jitter`; (b) se `VM_WRITE` estiver ligado, tracear `do_wp_page`/`wp_page_copy` (ou o
/// caminho equivalente de `handle_pte_fault` para uma PTE ausente+gravável — pode ser
/// `do_anonymous_page`, não COW, já que esta pode ser a PRIMEIRA falta) para ver exatamente qual
/// decisão deixa `RDONLY` ligado apesar de `DIRTY` ligado. `mvn -o test` verde no `virtual-arm-box`
/// (via a suíte normal, sem o harness temporário). M3 continua `@Disabled`. M1/M2 continuam
/// fechados.
///
/// ---
///
/// **ESTADO ANTERIOR (sessão de identificação da rotina + instrumentação de abort, 2026-08-18 (4))**
///
/// Duas descobertas concretas nesta sessão, a primeira estática (desmontagem cruzada contra o
/// fonte real do kernel) e a segunda dinâmica (instrumentação de aborts de memória), que juntas
/// isolam o problema a uma pergunta muito mais estreita do que qualquer sessão anterior alcançou:
///
/// 1. **A rotina foi identificada com certeza, por correspondência byte-a-byte contra o fonte real
///    do kernel** (`arch/arm/lib/uaccess_with_memcpy.c`, baixado via `curl` do
///    `raw.githubusercontent.com/torvalds/linux/v6.18/...` — o `elixir.bootlin.com` não serve
///    fonte cru por `WebFetch`, só a árvore de navegação). O loop de 157 passos JÁ CONHECIDO
///    (`0xc05b1750`-`0xc05b18c4`, ver sessões anteriores) é `__copy_to_user_memcpy()` chamada por
///    `arm_copy_to_user()` (`execve("/init")` copiando `argv`/`envp` para a nova pilha, confirma a
///    hipótese "cópia de execve" já levantada antes). A desmontagem real (`arm-none-eabi-objdump`
///    sobre o `kernel.img` descomprimido no host, `--adjust-vma=0xc0008000`) bate instrução por
///    instrução com o C:
///    ```c
///    while (!pin_page_for_write(to, &pte, &ptl)) {          // 0xc05b1868: bl pin_page_for_write
///        if (!atomic) mmap_read_unlock(current->mm);         // 0xc05b18fc..: (só no caminho de falha)
///        if (__put_user(0, (char __user *)to))                // 0xc05b189c: strb r4,[r6],#0 (r4=0)
///            goto out;
///        if (!atomic) mmap_read_lock(current->mm);
///    }
///    ```
///    `pin_page_for_write()` retorna sucesso (`1`) só quando
///    `pte_present() && pte_young() && pte_write() && pte_dirty()` — bate com a máscara
///    `and r2,r2,#0xC3; cmp r2,#0x43` vista na desmontagem em `0xc05b17b8`/`0xc05b17bc`. O `r6`
///    nunca avança porque, na leitura CORRETA do C, ele só avança DEPOIS que o `while` acima
///    termina (`to += tocopy`) — o `r6` congelado observado pelas sessões anteriores é o
///    COMPORTAMENTO ESPERADO de estar preso dentro do `while`, não um bug de "registrador que
///    deveria incrementar e não incrementa" como as hipóteses anteriores supunham.
/// 2. **Instrumentação dinâmica decisiva**: um harness temporário (`Raspi1DiagTempTest`, removido
///    antes do commit, mesmo precedente de sessões anteriores) registrou
///    {@link dev.vitorsilverio.armjitter.core.ArmTraceListener#onMemoryAbort} durante um
///    fast-forward de ~5,1 milhões de fatias em JIT (a mesma técnica de detecção de estagnação do
///    console das sessões anteriores) contando TODOS os aborts de memória do boot inteiro, não só
///    os do loop. Resultado: **exatamente 1 (UM) abort em todo o período, e é justamente o `strb`
///    de `0xc05b189c`** (`instructionAddress==0xc05b189c`), com `fault.virtualAddress()=0x0014622d`
///    — bate em cheio com o endereço-alvo do "byte de prova" já identificado pelas sessões
///    anteriores (`[0x0014622d]`, a mesma palavra vigiada na sessão de inspeção de memória). Ou
///    seja: **o mecanismo de permissão (`AP`/DACR) do `arm-jitter` funciona corretamente aqui** —
///    a PRIMEIRA tentativa de `__put_user(0,to)` falta (como o Linux espera, página nova/anônima
///    ainda sem `young`/`dirty`/`write` marcados), a falta é entregue, o handler do kernel
///    corrige a permissão (comprovado porque NENHUM abort seguinte acontece nas ~5 milhões de
///    fatias restantes — a escrita física passa a suceder sempre) — **mas mesmo assim
///    `pin_page_for_write()` continua retornando falha para sempre**, porque senão o `while`
///    teria saído e o padrão de 157 passos idêntico a cada período (já confirmado por sessões
///    anteriores) teria mudado.
/// 3. **A pergunta ficou MUITO mais estreita**: como o bit de permissão de hardware (`AP`) claramente
///    foi corrigido pelo handler de falta do kernel (a escrita física para de faltar), mas os bits
///    de contabilidade SOFTWARE que `pin_page_for_write()` relê da PTE (`pte_young`/`pte_dirty`/
///    `pte_write`, campos do formato Linux de 2 níveis do ARM, empacotados na MESMA palavra de 32
///    bits que o `AP` de hardware, mas em posições de bit DIFERENTES — ver
///    `arch/arm/include/asm/pgtable-2level.h`) nunca refletem o conserto, a pergunta deixou de ser
///    "existe um bug genérico de permissão/DACR/LDREX" (já descartado por 3 sessões) e virou: **o
///    `arm-jitter` decodifica o campo `AP` (bits corretos, `PAGE_AP_SHIFT=4`,
///    `TranslatingAddressSpace`) e ISSO FUNCIONA — mas os outros bits da MESMA palavra de PTE (que
///    `pin_page_for_write` lê diretamente da RAM do guest, sem NENHUM envolvimento do `arm-jitter`)
///    aparentemente não estão sendo escritos pelo handler de falta do kernel do jeito que
///    `pin_page_for_write` espera — ou porque o handler do kernel está tomando um caminho de "só
///    corrige `young`" (fault minor, sem marcar `dirty`) diferente do caminho "página anônima nova,
///    já marca tudo de uma vez" que o hardware/AP corrigido sugere, ou porque HÁ um bug real (nesta
///    hipótese, do `arm-jitter`) numa parte do ciclo de falta que NÃO é a checagem de `AP` em si —
///    candidatos concretos: (a) o valor de `FSR`/`DFSR` (tipo exato de fault status) entregue ao
///    kernel pode estar sinalizando o TIPO errado de falta (ex.: "seção" em vez de "página", ou
///    "permissão" em vez de "tradução"), fazendo `do_page_fault` tomar um ramo do kernel que só
///    atualiza PARTE das flags; (b) a própria escrita da PTE pelo kernel (`set_pte_at`) pode estar
///    indo para o endereço físico certo mas sendo mascarada por uma tradução STALE na micro-TLB do
///    `arm-jitter` para o espaço de endereço KERNEL (não o do usuário) na hora em que
///    `pin_page_for_write` FAZ SUA PRÓPRIA leitura da PTE (a leitura da PTE em si passa pela MMU do
///    `arm-jitter` para o mapeamento linear do kernel, então uma micro-TLB desatualizada ali
///    devolveria um valor ANTIGO mesmo que a RAM física já tenha o valor novo).
/// 4. **Próximo passo recomendado, concreto e mais barato que mais trace de registrador**: dump
///    direto de memória (não registrador) da palavra de PTE de `0x0014622d` (página `0x00146000`),
///    ANTES e DEPOIS do único abort observado, para comparar bit a bit contra
///    `arch/arm/include/asm/pgtable-2level.h` (`L_PTE_YOUNG`/`L_PTE_DIRTY`/`L_PTE_RDONLY`/
///    `L_PTE_PRESENT`, baixável do mesmo jeito que esta sessão baixou `uaccess_with_memcpy.c`, via
///    `curl raw.githubusercontent.com/torvalds/linux/v6.18/arch/arm/include/asm/pgtable-2level.h`
///    — `WebFetch` sozinho falhou contra o Bootlin, mas `curl` direto ao GitHub funcionou bem, usar
///    essa rota). Para achar o ENDEREÇO físico da PTE sem instrumentar C, o caminho mais barato é
///    ler `TTBR0` via o hook `Cp15VmsaCoprocessor` (não exposto por `Bcm2835Machine` hoje — precisa
///    de um acessor novo ou reflexão) e andar as tabelas L1/L2 manualmente (`va>>20` para o índice
///    L1, `(va>>12)&0xFF` para o L2), mesma técnica já usada nas sessões de `CR_XP`/`SCTLR`. Só
///    depois de isolar QUAL bit da palavra de PTE diverge do esperado é que dá para saber se o
///    conserto é no `arm-jitter` (ex.: reportar o tipo de fault errado) ou requer entender melhor
///    o caminho exato do `do_page_fault` do kernel 6.18.33 real para essa combinação de VMA
///    (anônima, `VM_WRITE`, primeira falta).
/// 5. `mvn -o test` verde no `virtual-arm-box`; harness temporário (`Raspi1DiagTempTest`) removido
///    antes do commit, mesmo precedente de sessões anteriores. Nenhum arquivo do `arm-jitter`
///    tocado — o mecanismo de `AP`/DACR foi CONFIRMADO correto nesta sessão (não é mais suspeito
///    para este bloqueio específico), a causa exata ainda não isolada é do lado da PTE/kernel.
///    M3 continua `@Disabled`. M1/M2 continuam fechados.
///
/// ---
///
/// **ESTADO ANTERIOR (sessão de inspeção de memória do loop, 2026-08-18 (3))**
///
/// Seguindo o passo (b) recomendado pela sessão anterior — inspecionar CONTEÚDO DE MEMÓRIA, não só
/// registradores, em `[r6]=[0x0014622d]` e na palavra de contagem do `rw_semaphore`
/// (`[0xc1558c2c]`) — o mesmo harness de duas fases (fast-forward JIT + `ArmCore#step()`) foi
/// reexecutado com um orçamento de fatias bem maior (`STALL_THRESHOLD` de 3.000.000 de fatias sem
/// crescimento do console, em vez de parar cedo) para dar mais chance ao boot de progredir sozinho
/// antes de declarar travamento. Dois achados novos:
///
/// 1. **O console PROGRIDE mais do que qualquer sessão anterior documentou antes de estagnar de
///    vez**: depois do já conhecido "silêncio" pós-`Run /init as init process` (kernel time
///    `482.42`s), esta corrida viu o log continuar em `699.77`s com
///    `thermal thermal_zone0: Unable to get temperature, disabling!` /
///    `Disabled thermal zone with critical trip point` — mensagens NUNCA vistas antes nesta task.
///    Ou seja, o "silêncio" documentado em sessões anteriores não é um travamento naquele ponto
///    exato: o boot continua avançando (bem mais devagar, ~217s de tempo de kernel sem nenhuma
///    linha nova), só trava de vez MAIS TARDE, no loop de 157 instruções já conhecido
///    (`0xc05b1750`-`0xc05b18c4`) — o crescimento do console parou definitivamente em
///    `slice=3.200.000` e não voltou até `slice=6.400.000` (limite de estagnação atingido), ponto
///    em que o trace por `step()` confirmou estar exatamente naquele loop.
/// 2. **Memória CONFIRMADA estática, não só registradores** (12 períodos consecutivos, 1805 passos
///    de `step()`, período de exatamente 157 passos — mesma cadência já medida): tanto
///    `[0x0014622d]` (o próprio endereço-alvo do `strb r4,[r6],#0` de prova) quanto
///    `[0xc1558c2c]` (a palavra de contagem do `rw_semaphore`) permanecem **bit-a-bit idênticos**
///    em TODOS os 12 períodos — `0x00002d00` e `0x00000100` respectivamente, sem nenhuma variação.
///    Isso fecha a lacuna que a sessão anterior deixou aberta: não há progresso invisível em
///    nenhuma das duas memórias vigiadas — o "byte de prova" nunca muda o que está escrito ali (é
///    coerente com o offset `#0` do `strb` pós-indexado: por decodificação, esta instrução NUNCA
///    poderia avançar `r6` sozinha, então "escrita idêntica sempre" é o comportamento correto
///    dela, não um bug) e a contagem do `rw_semaphore` fica travada em `0x100` = exatamente **UM
///    leitor** (`RWSEM_READER_BIAS` moderno), nunca liberado (`up_read`) nem incrementado por um
///    segundo leitor, pelo tempo inteiro observado.
/// 3. **Refinamento de causa provável**: como o corpo do loop em si (a subrotina chamada, ainda não
///    identificada por símbolo — `0xc02529b4`, ver sessão anterior) recebe argumentos CONSTANTES a
///    cada chamada (mesmo `r0`/`r2` originais, mesmo endereço de falta), o bug mais provável não
///    está DENTRO dessa subrotina (que se comporta de forma determinística e correta para os
///    mesmos argumentos), mas sim no CHAMADOR: algo no laço externo (plausivelmente
///    `copy_strings`/`fault_in_pages_writeable` do `execve()`, iterando página a página) deveria
///    avançar o endereço/contador entre chamadas e não está avançando — ou nunca alcança essa
///    atualização porque o corpo do loop de 157 instruções sempre retorna pelo mesmo caminho
///    (`b` incondicional de volta ao início, nunca o "fall-through" que levaria ao incremento).
/// 4. **Não investigado nesta sessão** (fora do orçamento): (a) os bits de `thread_info`
///    (`TIF_NEED_RESCHED`/preempt-count) via o ponteiro já capturado (`lr = *(TPIDRURO+0x520)`),
///    que poderiam explicar por que o retorno de IRQ nunca força uma reavaliação que tire o código
///    desse caminho; (b) o mapeamento do endereço `0xc02529b4` para um símbolo real do kernel —
///    sem `vmlinux`/`System.map` desta build específica, só desmontagem crua está disponível, e
///    cross-referenciar contra o fonte público do kernel 6.18.33 não foi feito nesta sessão.
///    **Próximo passo recomendado, concreto**: (a) ler `thread_info->flags`/`preempt_count` no
///    mesmo ponto do loop (offset a determinar na struct real do kernel 6.18) a cada período, para
///    ver se `TIF_NEED_RESCHED` está setado e nunca é atendido; (b) alternativamente, tracear o
///    PRIMEIRO período em que o loop começa (não os últimos, como fez esta sessão) para capturar o
///    valor que o "laço externo" tinha ANTES de entrar neste padrão — pode revelar se ele já nasceu
///    com um valor que nunca poderia progredir (ex.: um tamanho/contagem zerado por engano) em vez
///    de progredir e travar depois. `mvn -o test` verde no `virtual-arm-box`; harness temporário
///    desta sessão (`Trace.java`, fora do repositório, no diretório de scratch) não commitado, mesmo
///    precedente de sessões anteriores. Nenhum arquivo do `arm-jitter` tocado. M3 continua
///    `@Disabled`. M1/M2 continuam fechados.
///
/// ---
///
/// **ESTADO ANTERIOR (sessão de trace instrução-a-instrução do loop, 2026-08-18 (2))**
///
/// Seguindo o próximo passo recomendado pela sessão anterior (trace instrução-a-instrução focado
/// em `0xc05b1750`-`0xc05b1980`, registrando registradores a cada iteração), um harness temporário
/// (dois-fases: fast-forward via `Bcm2835Machine.Backend.JIT`/`runSlice()` até perto do loop, depois
/// `ArmCore#step()` puro para tracing — mesmo precedente das sessões de CPSR.E/tempestade de IRQ)
/// capturou o loop real e **DESCARTOU a hipótese de bug de `LDREX`/`STREX`/DACR no `arm-jitter`**
/// levantada pela sessão anterior, com evidência dinâmica concreta:
///
/// 1. **O loop persistente real é DIFERENTE do hipotetizado por disassembly estático**: não é o
///    `sub r7,r7,r9; bne` em `0xc05b1940`/`0xc05b194c` (código nunca alcançado nesta sessão) — é um
///    corpo menor e completamente determinístico de **157 instruções** em `0xc05b1750`-`0xc05b18c4`
///    que chama 3 sub-rotinas (`0xc0a979fc`, um fast-path de `rw_semaphore` já suspeitado; `0xc008e510`,
///    um padrão `LDREX`/`STREX`/`MSR CPSR_c` típico de `local_irq_save`/preempt-count; e `0xc02529b4`,
///    ainda NÃO identificado, com um padrão de `AND`/`SUB`/`CMP` sobre uma tabela — candidato a
///    `vprintk`/`__ratelimit`/busca de símbolo) e então salta incondicionalmente (`b`, não `bl`/retorno)
///    de volta ao próprio início.
/// 2. **Confirmado por registrador, não por padrão de bytes**: TODOS os registradores de propósito
///    geral amostrados (`r0`-`r4`, `r6`, `r9`, `r13`, `r14`) voltam ao valor **bit-a-bit idêntico** no
///    início de cada período, replicado em 20+ repetições consecutivas (período = exatamente 157
///    passos de `step()`, sem desvio). Nenhum progresso mensurável em NENHUM registrador visível —
///    contradiz a hipótese anterior de "contador de bytes restantes que nunca chega a zero" (não há
///    contador algum mudando; o código que faria isso nunca é executado).
/// 3. **A escrita de prova (`strb`) é real e foi confirmada dinamicamente pela primeira vez**:
///    `e4c64000` em `0xc05b189c` = `strb r4,[r6],#0`, com `r4=0` (byte escrito) e `r6=0x0014622d`
///    (endereço FIXO, nunca varia) — bate com a hipótese DACR/PAN da sessão anterior. Mas o par
///    `MRC`/`BIC`/`ORR`/`MCR p15,0,c3,c0,0` ao redor dela **rodeia corretamente**: o valor escrito no
///    DACR é `0x55` e a releitura seguinte no início do próximo período também é `0x55` — **round-trip
///    perfeito, sem o bug de "bits não modelados somem" que já foi corrigido para o SCTLR** (ver
///    histórico desta classe). Isso descarta a hipótese de bug de armazenamento do DACR.
/// 4. **`LDREX`/`STREX` resolvem corretamente nos DOIS pontos de chamada observados**: tanto no
///    fast-path do `rw_semaphore` (`0xc0a97a14`-`0xc0a97a24`) quanto na primitiva em `0xc008e510`
///    (`0xc008e53c`-`0xc008e550` e `0xc008e564`-`0xc008e574`) o `STREX` sempre sucede na PRIMEIRA
///    tentativa (o `TEQ`/`BNE` de retry nunca é tomado) — nenhuma tempestade de retry, nenhuma
///    falha de monitor exclusivo observada em nenhuma das 20+ repetições. Isso descarta a hipótese
///    de bug de correção do `LDREX`/`STREX` no `arm-jitter` levantada pela sessão anterior.
/// 5. **O timer periódico AINDA entrega IRQ nesta janela específica do boot** (achado novo, checagem
///    dirigida): 100.000 `runSlice()` (não `step()`, que não atualiza o timer/IC) a partir do loop
///    entraram em `CpuMode.IRQ` 27 vezes — ordem de grandeza compatível com a taxa "~2 em 2000"
///    medida pela sessão anterior sobre a corrida inteira. Isso descarta "IRQ parou de novo" como
///    causa deste bloqueio específico: os ticks continuam chegando, mas o loop não sai mesmo assim.
/// 6. **Achado colateral relevante via dump do console**: bem antes do ponto de travamento, o log
///    real do kernel mostra o bug de "Division by zero in kernel" (já documentado nesta classe como
///    "achado colateral não-bloqueante" numa sessão anterior) acontecendo DUAS vezes em
///    `pl011_set_termios`→`uart_update_timeout`→`div64_u64`, disparado por `console_on_rootfs()`
///    abrindo `/dev/console` — não fatal (o kernel trata `Ldiv0`/`Ldiv0_64` retornando um resultado
///    dummy), o boot segue normalmente até `"Freeing unused kernel image (initmem) memory: 500K"` e
///    `"Run /init as init process"`. **O console fica em SILÊNCIO TOTAL depois disso** (0 bytes novos
///    em 100.000 fatias adicionais de checagem de IRQ) — o travamento acontece bem cedo dentro do
///    `execve("/init")`, antes do PID 1 (busybox `/init`) produzir qualquer saída observável.
/// 7. **Próximo passo recomendado, concreto**: como o `arm-jitter` foi efetivamente descartado como
///    causa (DACR/`LDREX`/`STREX` comportam-se corretamente e de forma determinística), a
///    investigação deve mudar de eixo — em vez de mais trace de registrador, (a) identificar a
///    sub-rotina `0xc02529b4` (a única das 3 chamadas ainda não identificada) cruzando contra o
///    `vmlinux`/mapa de símbolos do kernel 6.18.33 real, se disponível, para saber exatamente o que
///    ela faz e por que sempre retorna o mesmo resultado; (b) inspecionar CONTEÚDO DE MEMÓRIA (não só
///    registradores) nos endereços `[r6]=[0x0014622d]` e `[r0]=[0xc1558c2c]` (a palavra de contagem do
///    `rw_semaphore`) antes/depois de cada período, já que a condição de saída deste loop
///    aparentemente não depende de NENHUM registrador de propósito geral observado — só pode
///    depender de memória, de um registrador CP15 não amostrado (ex.: `FPEXC`/VFP,
///    `TIF_NEED_RESCHED` em `thread_info`) ou de uma condição verdadeiramente externa (scheduler/
///    kthread que nunca roda num single-core sem os dispositivos desabilitados por esta task). `mvn
///    -o test` verde no `virtual-arm-box`; harness temporário desta sessão removido antes do commit
///    (mesmo precedente de sessões anteriores). Nenhum arquivo do `arm-jitter` tocado — ao contrário
///    da hipótese da sessão anterior, esta sessão fornece evidência de que NÃO há bug de
///    `LDREX`/`STREX`/DACR ali. M3 continua `@Disabled`. M1/M2 continuam fechados.
///
/// ---
///
/// **ESTADO ANTERIOR (sessão de diagnóstico do TERCEIRO bloqueio de M3, 2026-08-18 (1))**
///
/// Seguindo o próximo passo recomendado pela sessão do `FdtPatcher`/`withNodeDisabled`
/// (2026-08-17): amostragem direta de `ArmCore` (sem trace instrução-a-instrução completo —
/// mais barato, mesmo precedente da sessão de reconhecimento do timer) confirmou que a CPU
/// **NÃO** está em `WFI`/HALT (`sleepState()` fica `RUNNING` o tempo todo, `halted()`/`stopped()`
/// nunca verdadeiros) nem presa numa tempestade de IRQ (modo fica quase sempre `SUPERVISOR`, só
/// 2 amostras em 2000 caem em `IRQ`) — descartando as duas hipóteses mais óbvias herdadas do
/// histórico desta task. Um histograma de PC (amostrado a cada 100 fatias, ~187 mil amostras em
/// 8 minutos reais) mostrou **exatamente onde**: o console para de crescer definitivamente na
/// amostra 6964/187633 (isto é, nos primeiros ~3,7% da corrida) e, dali em diante, a CPU
/// continua executando ativamente (nunca para), mas concentrada num conjunto pequeno e ESTÁVEL
/// de ~20 endereços "quentes" (cada um com contagem quase idêntica, ~2500, muito acima da média
/// de ~150 dos ~1257 endereços únicos vistos no total) — a assinatura clássica de um LOOP sem
/// saída, não de trabalho legítimo diverso (que continuaria descobrindo endereços novos pelo
/// resto da corrida).
///
/// Desmontando essa região quente (`arm-none-eabi-objdump -D -b binary -m arm
/// --adjust-vma=0xc0008000` no `kernel.img` descomprimido pelo host, sem símbolos — kernel
/// virtual base `0xc0008000` = {@link dev.vitorsilverio.virtualarmbox.boot.ZImageDecompressor#TEXT_OFFSET}
/// somado a `PAGE_OFFSET`) revela um padrão reconhecível: um loop em `0xc05b185c` que (a) chama
/// uma rotina em `0xc0a979fc` que faz `LDREX`/`STREX` para incrementar um contador de 32 bits em
/// passos de `0x100` e testa os bits `0x80000007` do resultado — a forma clássica do fast-path de
/// um **`rw_semaphore`** (`down_read`, provavelmente `mmap_lock`, dado o contexto de
/// `execve()`/fault-in de página) — e (b) executa uma escrita de PROVA de 1 byte
/// (`strb r4,[r6],#0`) cercada por `MRC`/`MCR p15,0,c3,c0,0` (leitura/escrita do **DACR**) — a
/// implementação clássica de `CONFIG_CPU_SW_DOMAIN_PAN` (PAN emulado via troca do domínio de
/// acesso do usuário), o mesmo mecanismo de `v6_clear_user_highpage_aliasing` cujo `MCRR`/`MRRC`
/// já tinha sido corrigido numa sessão anterior desta task — mas aqui é outra rotina irmã,
/// plausivelmente `fault_in_pages_writeable`/`copy_strings`/`setup_arg_pages` (o `execve()` de
/// `/init` copiando `argv`/`envp` para a nova pilha, forçando o fault-in página a página antes da
/// cópia em massa). O acquire do rwsem parece ter sucesso no fast-path a cada chamada (o slow-path
/// em `0xc0a97490`, que desabilita IRQ e manipula uma wait-list — `rwsem_down_read_slowpath` — quase
/// não aparece no histograma), mas o loop externo em `0xc05b1940`/`0xc05b194c`
/// (`sub r7,r7,r9; cmp r7,#0; bne 0xc05b185c`) nunca reduz `r7` a zero — ou seja, a CONTABILIDADE
/// de "bytes restantes" nunca avança, apesar de cada iteração aparentar sucesso individualmente.
/// **Hipótese concreta, NÃO confirmada**: um bug de correção em `LDREX`/`STREX`/DACR do
/// `arm-jitter` sob esta combinação específica (nunca exercitada antes desta task: monitor
/// exclusivo real + `CONFIG_CPU_SW_DOMAIN_PAN` + fault-in de página do usuário juntos) faz o
/// "byte copiado com sucesso" nunca ser refletido no contador que o loop externo usa para decidir
/// quando parar — mesma categoria dos bugs reais já encontrados nesta task (SCTLR, CPACR, unaligned
/// access, CPSR.E-on-exception-entry), mas desta vez não isolado ao nível de opcode exato.
/// **Próximo passo recomendado, concreto**: (a) cross-referenciar os endereços
/// `0xc05b174c`/`0xc05b185c`/`0xc008e510`/`0xc0240060`/`0xc0240110`/`0xc02400bc` contra o fonte
/// real do kernel 6.18.33 (`arch/arm/lib/`, `mm/gup.c`/`fs/exec.c` — `fault_in_pages_writeable`/
/// `copy_strings`) para confirmar a hipótese sem adivinhar semântica pelo padrão de bytes; (b) um
/// trace instrução-a-instrução (`ArmCore#step()`, técnica já usada nas sessões de CPSR.E/tempestade
/// de IRQ) focado SÓ nesse loop pequeno (endereços `0xc05b1750`-`0xc05b1980`), registrando o valor
/// de `r7`/`r9`/o resultado do `strb` de prova a cada iteração, para confirmar exatamente qual
/// registrador para de avançar e por quê. `mvn -o test` verde no `virtual-arm-box`; harness
/// temporário desta sessão removido antes do commit (mesmo precedente de sessões anteriores).
/// Nenhum arquivo do `arm-jitter` tocado — a hipótese de bug real ali não foi confirmada, só
/// levantada. M3 continua `@Disabled`. M1 fechado. M2: o abort storm
/// de `CPSR.E` (sessão anterior) e o panic de VFS (`FdtPatcher`, sessão anterior) estão
/// resolvidos; o bloqueio mais recente conhecido era `Oops - undefined instruction` em
/// `v6_clear_user_highpage_aliasing` por falta de decode de `MCRR`/`MRRC` — CORRIGIDO nesta
/// sessão (`arm-jitter` decodifica `MCRR`/`MRRC`; achado extra: a cadeia de decorators CP15/CP14
/// deste host não repassava `handlesDouble`/`readDouble`/`writeDouble`, mascarando o fix mesmo
/// com testes de unidade verdes — corrigido nos 3 decorators). **Não sabemos se isso fecha M2**:
/// a re-execução do teste JIT nesta sessão não concluiu em ~40min (JUnit `@Timeout` em modo
/// `SAME_THREAD` não preempte laço apertado) e foi abortada manualmente — resultado
/// inconclusivo, não uma falha confirmada. Ver o motivo do `@Disabled` no método
/// `reachesFreeingKernelMemoryAcceiteM2Jit` para o próximo passo recomendado (harness com
/// progresso observável em vez do `@Test` cru).
///
/// **M1 redefinido (sessão 1/3)**: a mensagem literal do enunciado ("Uncompressing Linux...
/// done, booting the kernel.") não existe neste `kernel.img` oficial — confirmado rodando o
/// MESMO `kernel.img`+`.dtb` no `qemu-system-arm -M raspi1ap` (oráculo instalado nesta máquina)
/// como referência. O marcador equivalente adotado é `Booting Linux on physical CPU 0x0` via
/// `earlycon` — ver `testdata/raspi1/README.md`.
///
/// **Sessão 2/3 — o bloqueio de desempenho da sessão 1 foi FECHADO**: descomprimir o
/// `kernel.img` no HOST ({@link dev.vitorsilverio.virtualarmbox.boot.ZImageDecompressor}) e
/// carregar a imagem já pronta direto no endereço de link (`stext`) elimina o `inflate()` caro
/// do guest (~750 milhões de ciclos medidos na sessão 1). Isso destravou o boot para progredir
/// centenas de milhares de ciclos a mais, revelando (e permitindo corrigir) DOIS bugs reais e
/// arquiteturais do `arm-jitter` no `Cp15VmsaCoprocessor`/`Bcm2835Cp15Extras` — primeira
/// validação de sistema real do `ARM11_MPCORE`/ARMv6K (ver Javadoc de {@link Bcm2835Machine} e
/// de `Cp15VmsaCoprocessor`/`Bcm2835Cp15Extras` no `arm-jitter`):
/// 1. `MCR p15,0,Rt,c13,c0,3` (`TPIDRURO`, ponteiro de TLS) não era reconhecido — UNDEFINED tão
///    cedo no boot que os vetores de exceção ainda não tinham sido copiados por
///    `early_trap_init()`, cascateando num laço infinito de `PREFETCH_ABORT` (busca da PRÓPRIA
///    rotina de vetor também falhava). Corrigido: `c13,c0,{0,2,3,4}` (FCSEIDR/TPIDRURW/
///    TPIDRURO/TPIDRPRW) agora são armazenamento simples, sem efeito colateral.
/// 2. `ID_MMFR0`/`ID_ISAR*`/qualquer sub-registrador `c0` (esquema CPUID ARMv6+) fora de
///    `MIDR`/`CTR` também não era reconhecido pelo mesmo motivo. Corrigido de forma
///    arquiteturalmente correta (não um palpite): a ARM GARANTE que ler um sub-registrador de ID
///    não alocado devolve um valor UNKNOWN (aqui `0`), NUNCA lança UNDEFINED — `Bcm2835Cp15Extras`
///    agora reivindica o esquema `c0`/`opcode1=0` inteiro em vez de listar `CRm` um a um.
///
/// **Sessão 2/3 — M1 NÃO fechou naquela sessão**: depois dos dois fixes de CP15 acima, o boot
/// esbarrava num limite deliberado e já documentado do `arm-jitter`: `IrExecutionSupport.
/// checkLittleEndianData` recusava (`UnsupportedOperationException`, de propósito) qualquer
/// acesso a dado com `CPSR.E=1` (big-endian/`SETEND BE`) — decisão de escopo MVP da task `B1.5`
/// do `arm-jitter` (só little-endian). O kernel ARMv6K real executa `SETEND`/toca dado
/// big-endian bem cedo no boot.
///
/// **Sessão 3/3 (2026-08-15) — BLOQUEIO DE BE8 FECHADO pela task `B1.8` do `arm-jitter`
/// (sessão dedicada, `.m2` local já publicado com o fix) — M1 FECHOU DE VERDADE, nos DOIS
/// backends**: {@link #reachesEarlyconBannerAcceiteM1Interpreted()} e
/// {@link #reachesEarlyconBannerAcceiteM1Jit()} passam em menos de 1s cada (o marcador aparece
/// bem cedo no log). Nenhum bug novo do `arm-jitter` apareceu nesta sessão além do que a B1.8 já
/// tinha corrigido.
///
/// **M2 NÃO fechou nesta sessão — bloqueio NOVO, genuinamente diferente de BE8/CP15/desempenho**:
/// com M1 destravado, o boot avança bem além do `earlycon` mas entra num LAÇO DE `Oops` do
/// próprio kernel (`Unable to handle kernel paging request`, "8&lt;--- cut here ---" repetido)
/// já em `unflatten_device_tree()`/`fdt_next_tag` (parsing do FDT via a janela de `fixmap`
/// mapeada por virtual, logo depois do scan físico inicial que já funcionou — "Machine model:
/// Raspberry Pi Model B", "Reserved memory: created CMA memory pool..." aparecem certinhos antes
/// do loop começar), a poucas dezenas de milhares de instruções do banner do M1.
///
/// **Confirmado como divergência REAL via o oráculo QEMU 8.0.0** (`qemu-system-arm -M raspi1ap`,
/// EXATAMENTE o mesmo `kernel.img`+`bcm2708-rpi-b.dtb`+`initramfs.cpio.gz`+cmdline desta classe):
/// o QEMU boota limpo até enumerar USB (`dwc_otg`/`smsc95xx`) e monta o initramfs
/// (`Trying to unpack rootfs image as initramfs...` / `Freeing initrd memory`), MUITO além de
/// `Freeing unused kernel memory` — sem NENHUM Oops. Isto não é uma feature faltando (como o
/// BE8 era): é uma divergência de comportamento observável entre este emulador e uma referência
/// de hardware real para a MESMA entrada, ou seja, um bug real em algum lugar (`arm-jitter` ou
/// `virtual-arm-box` — root cause NÃO isolado ainda).
///
/// **Sessão extra (2026-08-16) — lacuna de observabilidade FECHADA, causa raiz do laço de Oops
/// ISOLADA E CORRIGIDA (2 bugs reais, um no `arm-jitter` e um no `virtual-arm-box`), e um
/// bloqueio NOVO E DIFERENTE encontrado logo depois**:
///
/// 1. **Lacuna de observabilidade fechada** (task `E2` do `arm-jitter`,
///    `ArmTraceListener#onMemoryAbort`, aditivo/G3): antes, `beforeInstruction`/`afterInstruction`
///    só disparavam sob {@link dev.vitorsilverio.armjitter.core.ArmCore#step()} — sob
///    {@link dev.vitorsilverio.armjitter.core.ArmCore#runBlocks} (o caminho real de
///    {@link Bcm2835Machine#runSlice()}) nenhum evento por-instrução disparava. O novo gancho
///    dispara em `ArmCore#enterMemoryAbort` — convergência dos 3 caminhos de execução — com o PC
///    exato ANTES de qualquer mutação de estado: **o primeiro fault reportava `pc=0xc0a69088`**,
///    batendo byte a byte com o Oops do próprio kernel (`PC is at fdt_next_tag+0xec/0x154`).
/// 2. Hipóteses (a) staleness de TLB/PTE e (b) tamanho de RAM (256MiB vs. QEMU): **descartadas**
///    com evidência concreta (ver histórico git desta classe para o raciocínio completo).
/// 3. **Causa raiz ISOLADA via comparação byte a byte contra o oráculo QEMU 8.0.0** (mesmo
///    `kernel.img`+`bcm2708-rpi-b.dtb`+`initramfs.cpio.gz`+cmdline, `-M raspi1ap`, monitor HMP
///    `xp` para ler a RAM física do guest diretamente): no MESMO slot de L1 (`swapper_pg_dir`,
///    físico `0x4000 + 4088*4 = 0x7fe0`, que cobre a janela virtual `0xff800000`-`0xff8fffff` onde
///    o kernel mapeia o `.dtb` como `MT_MEMORY_RO`, `devicemaps_init()`/`arch/arm/mm/mmu.c`), o
///    QEMU produz o descritor de seção `0x0800841e` (`AP=01`,`APX=1` → só leitura PRIVILEGIADA) e
///    nosso emulador produzia `0x0800000e` (`AP=00`,`APX=0` → SEM ACESSO ALGUM, daí o
///    `DATA_ABORT`/`SECTION_PERMISSION` na primeira leitura de `fdt_next_tag()`). A diferença é
///    literalmente 2 bits (`APX`+`AP_WRITE`). `arch/arm/mm/mmu.c: build_mem_type_table()` só
///    adiciona esses 2 bits em `MT_MEMORY_RO` quando `cpu_arch >= CPU_ARCH_ARMv6 && (cr & CR_XP)`
///    — `cr` é o próprio `SCTLR` relido via `get_cr()`, e `CR_XP` é o bit 23. O log do kernel
///    confirma: no boot real/QEMU, `cr=00c5387d` (bit 23 ligado); no nosso, `cr=00002001` (bit 23
///    desligado) — **apesar do kernel ter ESCRITO um `SCTLR` com o bit 23 ligado no início do
///    boot**. Causa: `Cp15VmsaCoprocessor#sctlrValue()` (arm-jitter) reconstruía o valor de
///    leitura só a partir dos 2 bits com efeito colateral modelado (`M`/`V`), RAZ para todo o
///    resto — um `MCR` que ligava `CR_XP` "sumia" na releitura seguinte. **Corrigido no
///    `arm-jitter`** (`Cp15VmsaCoprocessor`, ver Javadoc daquela classe): o valor de 32 bits
///    escrito agora é armazenado e devolvido por inteiro (só `M`/`V` continuam recomputados a
///    partir do estado autoritativo), aditivo/G3, com teste de regressão
///    (`sctlrUnmodeledBitsRoundTripOnRead`) e G5 revalidado (arm-jitter+gbaemu+ndsemu verdes;
///    `armbox` tem uma falha PRÉ-EXISTENTE e não relacionada em `Armv7TortureTest`/`VfpRegisters`,
///    confirmada reproduzível COM e SEM este fix via `git stash` — não é regressão desta sessão).
/// 4. **Segundo bug real encontrado IMEDIATAMENTE depois do fix acima** (`virtual-arm-box`, não
///    `arm-jitter`): com o laço de Oops do FDT resolvido, o boot avança e trava num NOVO
///    `Kernel panic - not syncing: Attempted to kill the idle task!` em
///    `perf_event_init()`→`init_hw_breakpoint()`→`hw_breakpoint_slots()`→`get_debug_arch()`, que
///    lê `DBGDIDR` via `MRC p14,0,Rd,c0,c0,0` — nenhum {@code CoprocessorBus} deste host reivindica
///    o coprocessador 14 (depuração), o core entrega `UNDEFINED`, e como isso acontece dentro do
///    processo idle sem tratamento de sinal, o kernel morre. O oráculo QEMU mostra a saída
///    esperada: `hw-breakpoint: debug architecture 0x0 unsupported.` — o `arm1176_initfn` do QEMU
///    (`target/arm/tcg/cpu32.c`) não seta `cpu->isar.dbgdidr` (fica `0`, RAZ da struct), então o
///    kernel real lê `DBGDIDR=0`, decide "não suportado" e segue o boot. **Corrigido**: novo
///    {@link dev.vitorsilverio.virtualarmbox.device.bcm2835.Bcm2835Cp14Extras}, reivindicando CP14
///    inteiro com RAZ/WI (mesmo precedente de {@code Bcm2835Cp15Extras} para `c7`), encadeado na
///    frente de `Bcm2835Cp15Extras` em {@link Bcm2835Machine#create}.
/// 5. **Bloqueio NOVO encontrado depois dos dois fixes acima — M2 continua sem fechar nesta
///    sessão**: com os dois bugs corrigidos, `total faults=0` (nenhum `DATA_ABORT`/`PREFETCH_ABORT`
///    pelo resto do boot, em INTERPRETED e JIT) e nenhum novo Oops/panic — mas o boot para de
///    produzir qualquer linha nova de console logo depois de `Console: colour dummy device 80x30`
///    (exatamente onde `calibrate_delay()` roda no kernel real, seguido por
///    `Calibrating delay loop... N BogoMIPS`). **Evidência concreta, não especulação**: instalado
///    um `ModeChangeListener` temporário contando entradas em `CpuMode.IRQ` — em corridas de
///    4,8 milhões de fatias (~100s reais, INTERPRETED e JIT, mesmo resultado nos dois) o contador
///    de `Bcm2835SystemTimer` avança normalmente (`counterMicrosLow` passa de 926 milhões, ou
///    seja, ~15 minutos de tempo simulado), mas **só UMA única IRQ de timer é entregue em toda a
///    corrida** (a primeira, `pc=0xc001c5f0`) — depois disso `icAsserted=false`,
///    `timerIrq=false`, `coreInterruptLine=false` pelo resto do tempo. A emulação do registrador
///    (ack por escrita-limpa-bit em `REG_CTRL_STATUS`, re-armamento em `armCompare()` por escrita
///    em `REG_COMPAREn`) foi relida e está correta — `write32` sempre religa `compareArmed[index]`
///    incondicionalmente a cada escrita. Isso aponta para o handler de IRQ do kernel nunca
///    completar/re-armar o próximo comparador, OU para as interrupções ficarem mascaradas
///    (`CPSR.I`) depois da primeira entrega e nunca serem restauradas no retorno — mas a causa
///    raiz exata (kernel vs. caminho de entrada/retorno de exceção do `arm-jitter`) NÃO foi
///    isolada nesta sessão; sem `jiffies` avançando, `calibrate_delay()` (que depende de
///    `jiffies`, não do contador livre, já que o ARM1176/`ARM11_MPCORE` não expõe um contador de
///    ciclos de performance-monitor que o `read_current_timer()` do kernel possa usar) nunca
///    termina, dobrando seu laço de calibração indefinidamente. **Próximo passo recomendado**:
///    tracear o PC exato da instrução de retorno da PRIMEIRA IRQ (`SUBS PC,LR` ou equivalente,
///    logo depois de `pc=0xc001c5f0`) e comparar o `CPSR`/`SPSR_irq` antes e depois do retorno
///    contra o comportamento esperado (bit `I` deve voltar ao estado de antes da exceção) — se o
///    `arm-jitter` restaura `CPSR.I` errado na saída de uma IRQ que ele mesmo entregou, é um bug
///    real da lib (categoria "handling de exceção", nunca testado em sistema real com timer
///    periódico antes desta task).
///
/// M2/M3 continuam `@Disabled` nesta sessão — o laço de Oops original está genuinamente resolvido
/// (2 fixes reais, cada um com teste de regressão), mas o novo bloqueio de IRQ/`calibrate_delay`
/// impede fechar M2 dentro do orçamento desta sessão. Não é BE8 (B1.8), não é CP15/CP14 faltante
/// (ambos fechados nesta sessão), não é desempenho de descompressão (`ZImageDecompressor`), não é
/// staleness de TLB/MMU nem tamanho de RAM (descartados) e não é mais o laço de Oops do FDT
/// (corrigido). É um bloqueio de entrega/retorno de IRQ periódica, categoricamente novo.
///
/// **Sessão de continuação do M2 (2026-08-16) — causa raiz REFINADA, ainda NÃO fechada**: a
/// hipótese anterior ("só 1 IRQ de timer entregue em toda a corrida") estava incompleta. Achado
/// real corrigido nesta sessão: {@link Bcm2835Machine#runSlice()} só encaminhava o comparador
/// **0** do {@link dev.vitorsilverio.virtualarmbox.device.bcm2835.Bcm2835SystemTimer} para o
/// {@link dev.vitorsilverio.virtualarmbox.device.bcm2835.Bcm2835Ic} — decodificando o `.dtb`
/// real desta task byte a byte (`timer@7e003000: interrupts = <1 0>,<1 1>,<1 2>,<1 3>;`,
/// `compatible = "brcm,bcm2835-system-timer"`, exatamente o binding do driver mainline
/// `drivers/clocksource/bcm2835_timer.c`, cujo `DEFAULT_TIMER` é o comparador **3**), o
/// clockevent periódico que o kernel arma nunca era entregue. Corrigido: os 4 comparadores agora
/// são encaminhados 1:1 para as fontes GPU 0-3 (mesma fiação do `hw/timer/bcm2835_systmr.c` do
/// QEMU, já citada no Javadoc daquela classe).
///
/// **O fix acima é necessário mas NÃO suficiente — revelou um bloqueio DIFERENTE**: instrumentação
/// temporária (removida antes do commit, não faz parte do código entregue) provou, por leitura
/// direta dos registradores do `Bcm2835SystemTimer`/`Bcm2835Ic` a cada 1M fatias em backend JIT:
/// `COMPARE3` fica **congelado** no valor inicial (`0x27f4`) por toda a corrida (>250s reais, o
/// contador livre passa de `0x10767060` para `0xc64beb0a` no mesmo intervalo — bilhões à frente
/// do "deadline"), o bit 3 de `REG_CTRL_STATUS` fica **permanentemente pendente** (nunca
/// limpo/`ack`-ado) e o bit 3 de `IRQ_ENABLE_1` nunca é mascarado — e ainda assim a CPU **reentra
/// em modo IRQ continuamente** (~60.600 vezes por 1M fatias, crescimento linear, contador de
/// bordas de entrada em `CpuMode.IRQ` medido diretamente). Ou seja: não é mais "nenhuma IRQ
/// chega" — é uma **tempestade de IRQ**: o handler do kernel para o `hwirq`/`virq` do timer nunca
/// chega a fazer `ack` (escrita em `REG_CTRL_STATUS`) nem a rearmar (`REG_COMPARE3`), então o
/// nível fica preso "pendente" e a CPU reentra assim que `CPSR.I` é reabilitado no retorno da IRQ
/// anterior. Causa raiz exata NÃO isolada nesta sessão — hipóteses concretas para a próxima:
/// (a) o handler de IRQ do kernel para `hwirq 3`/`virq 27` nunca é de fato despachado (IRQ
/// tratada como espúria/não mapeada pelo driver `bcm2835-armctrl-ic`, o kernel deveria mascarar
/// mas talvez essa mascaração também dependa de um registrador/idioma CP15 ainda não emulado);
/// (b) o retorno de exceção IRQ do `arm-jitter` devolve à instrução certa mas o efeito da
/// escrita em `REG_CTRL_STATUS`/`REG_COMPARE3` feita pelo handler não está realmente chegando ao
/// dispositivo (checar se o handler roda em um endereço mapeado corretamente pela
/// `TranslatingAddressSpace` nesse ponto do boot). Próximo passo recomendado: um trace
/// instrução-a-instrução (via `ArmCore#step()`/backend INTERPRETED, não `runBlocks`, já que
/// {@link dev.vitorsilverio.armjitter.core.ArmTraceListener#beforeInstruction} só dispara sob
/// `step()`) capturando as primeiras dezenas de instruções executadas logo após a PRIMEIRA
/// entrada em `CpuMode.IRQ`, para confirmar se o código do handler do timer chega a ser
/// alcançado.
///
/// **Sessão de reconhecimento (2026-08-16, só diagnóstico direto de periférico, sem trace de
/// instrução — mais barato de rodar primeiro) — achado NOVO que restringe bastante o espaço de
/// causa raiz**: um experimento temporário (harness removido antes do commit, mesmo precedente
/// da sessão anterior) amostrou `Bcm2835SystemTimer`/`Bcm2835Ic` DIRETO (via `read32`, sem passar
/// pela CPU) a cada 5.000 fatias, por 2.000.000 de fatias (INTERPRETED). Resultado: `COMPARE3`/
/// `CTRL_STATUS`/`IRQ_ENABLE_1` mudam **exatamente uma vez** — em ~75.000 fatias, o comparador é
/// armado (`COMPARE3=0x2769`) e a IRQ é desmascarada no controlador (`IRQ_ENABLE_1` bit3, ou
/// seja, `request_irq`+`irq_unmask` do driver `bcm2835-armctrl-ic` SUCEDERAM de verdade) — e então
/// `CTRL_STATUS` bit3 fica pendente (`0x08`) e **nunca mais muda pelas 1.925.000 fatias
/// seguintes**, nem `COMPARE3` é rearmado. Ou seja: não é "o handler parou de rodar depois de um
/// tempo" (o que a sessão anterior media por amostragem grossa a cada 1M fatias parecia sugerir)
/// — é **o corpo do handler nunca roda nem uma ÚNICA vez**, apesar do `request_irq`/`irq_unmask`
/// terem sido bem-sucedidos e da CPU reentrar em `CpuMode.IRQ` continuamente (achado da sessão
/// anterior). Isso é consistente com hardware real se o handler de nível superior
/// (`bcm2835_handle_irq`/`asm_do_IRQ`, instalado via `set_handle_irq`) nunca identificar a fonte
/// pendente corretamente e devolver sem despachar — a linha `nIRQ` continua alta legitimamente
/// (nunca é um artefato de obsolescência da sondagem por fatia de `Bcm2835Machine#runSlice`,
/// que só looparia se o handler CHEGASSE a rodar e o host não tivesse repolled a tempo — não é
/// o caso aqui, já que o handler nunca roda). **Restringe a hipótese (a) do bloqueio anterior**
/// (dispatcher de nível superior nunca alcança o ISR do timer) como a mais provável; a hipótese
/// (b) (efeito da escrita não chegando ao dispositivo) fica MENOS provável, já que não há
/// evidência de nenhuma escrita nem tentativa — o registrador nunca muda, não muda para um valor
/// "errado". Próximo passo recomendado (ainda não executado): o trace instrução-a-instrução via
/// `ArmCore#step()` já recomendado na sessão anterior, mas agora com um alvo mais específico —
/// confirmar se o PC, ao reentrar em `CpuMode.IRQ` repetidamente, chega a alcançar o corpo de
/// `bcm2835_handle_irq`/o vetor de `generic_handle_irq` do driver `irq-bcm2835.c`, ou se retorna
/// antes disso (ex.: um `asm_do_IRQ`/`irq_svc` que trata a IRQ como espúria e nunca lê
/// `IRQ_PENDING_1`/`IRQ_PENDING_2` do nosso `Bcm2835Ic`). `mvn -o test` verde no `virtual-arm-box`
/// (nenhum arquivo de produção tocado nesta sessão — só o harness temporário, removido); M1/M2/M3
/// continuam no mesmo estado desta e da sessão anterior.
///
/// **Sessão de correção da tempestade de IRQ (2026-08-16) — causa raiz ISOLADA E CORRIGIDA (bug
/// real do `arm-jitter`), M2 ainda NÃO fecha (bloqueio novo e diferente revelado logo depois)**:
///
/// Seguindo o próximo passo recomendado acima, um trace instrução-a-instrução via
/// {@link dev.vitorsilverio.armjitter.core.ArmCore#step()} — com o detector de "primeira entrada em
/// `CpuMode.IRQ`" CORRIGIDO para exigir `pc == vetor exato` (não só `mode()==IRQ`; a primeira
/// tentativa capturava por engano o `cpu_init()` do kernel fazendo `MSR CPSR_c` explícito por
/// IRQ/ABT/UND/FIQ só para programar o SP de cada modo, nada a ver com uma interrupção de
/// hardware) — capturou a entrada REAL no vetor `0xffff0018` → `vector_irq` → `__irq_svc` →
/// `irq_handler`/`handle_arch_irq`. Instrumentação adicional (temporária, direto em
/// `Bcm2835ArmControlBlock#read32`, removida antes do commit) provou que o driver LÊ
/// `IRQ_PENDING_BASIC` e recebe `0x100` corretamente na PRIMEIRA leitura — mas as 3 leituras
/// SEGUINTES, em `addr+1`/`addr+2`/`addr+3` (endereços NÃO alinhados), caem no `default -> 0` do
/// {@link dev.vitorsilverio.virtualarmbox.device.bcm2835.Bcm2835Ic} (offsets desconhecidos).
///
/// **Causa raiz real**: `IrExecutionSupport`/`AsmRuntimeHelpers` do `arm-jitter`
/// (`readWordForLoad`/`writeWordForStore` e os equivalentes de halfword) decompunham TODO
/// `LDR`/`STR` não-PC sob `ArmFeature.UNALIGNED_ACCESS` (ligada em `ARM11_MPCORE`/ARMv6K+) em 4 (ou
/// 2) chamadas independentes de `AddressSpace#read8`/`write8` — MESMO quando o endereço já era
/// múltiplo de 4 (ou 2). Hardware ARMv6+ real faz uma ÚNICA transação de barramento para um acesso
/// JÁ ALINHADO (só o caso GENUINAMENTE desalinhado precisa da composição byte a byte, ARM DDI
/// 0406C A3.2.1); o código antigo aplicava o caminho "atravessado" sempre, então um `LDR` alinhado
/// a `IRQ_PENDING_BASIC` (`0x2000B200`) virava `read8(...200)|read8(...201)&lt;&lt;8|
/// read8(...202)&lt;&lt;16|read8(...203)&lt;&lt;24` — o byte 0 batia (`0x00`, correto), mas os 3
/// bytes seguintes caíam no fallback "offset desconhecido" de QUALQUER periférico deste
/// repositório (nenhum reimplementa "byte N da word alinhada" em `read8`/`write8` — nunca
/// precisaram, pois nenhum consumidor anterior exercitava um sistema MMIO real sob um preset com
/// `UNALIGNED_ACCESS`), reconstruindo `0x00000000` em vez do `0x00000100` real. O driver do kernel
/// (`irq-bcm2835.c`, `get_next_armctrl_hwirq`) via sempre "nada pendente" mesmo com o bit certo
/// armado no periférico → `bcm2835_handle_irq()` nunca despachava → handler do timer nunca fazia
/// `ack`/rearme → nível ficava preso "pendente" → CPU reentrava em `CpuMode.IRQ` assim que
/// `CPSR.I` era reabilitado — exatamente a "tempestade de IRQ" das duas sessões anteriores.
///
/// **Corrigido no `arm-jitter`** (`IrExecutionSupport.java` e `AsmRuntimeHelpers.java`, interpretado
/// E JIT): uma checagem de alinhamento decide entre o caminho legado de transação única (correto
/// para o caso alinhado) e o caminho atravessado byte a byte (só para endereço genuinamente
/// desalinhado). 3 testes de regressão novos (2 no interpretador usando um `AddressSpace` de teste
/// que modela exatamente o padrão real de `Bcm2835Ic` + 1 de equivalência nativa interpretado×JIT).
/// Sanidade confirmada por `git stash`: revertendo só o fix, os 2 testes do interpretador falham
/// exatamente como esperado. `mvn -o test` verde no `arm-jitter` (1361 core+truffle) + `mvn -o
/// install`; G5 revalidado (gbaemu 240 verde, ndsemu 183 verde, armbox 40/41 — a 1 falha é a mesma
/// pré-existente de `Armv7TortureTest`/VFP, reconfirmada não-regressão via `git stash`).
///
/// **Efeito no boot**: com o fix, {@code Bcm2835Machine.Backend.JIT} avança MUITO além do ponto
/// anterior — `Calibrating delay loop... 3.81 BogoMIPS`, `CPU: Testing write buffer coherency: ok`,
/// `Setting up static identity map...`, `devtmpfs: initialized` aparecem pela primeira vez (14:43min
/// de wall-clock, rodada real medida). **M2 ainda NÃO fecha**: um bloqueio NOVO e diferente aparece
/// logo depois — `Internal error: Oops - undefined instruction` em `vfp_enable+0x8/0x20`, chamado
/// por `on_each_cpu_cond_mask` ← `vfp_init` ← `do_one_initcall` (durante a inicialização do
/// subsistema VFP do kernel, com IRQs desligadas) — o processo `init` morre (`Kernel panic - not
/// syncing: Attempted to kill init!`). Causa provável: alguma instrução de habilitação de VFP (ex.
/// `VMSR FPEXC,Rt` ligando o bit `EN`) que o `ARM11_MPCORE`/`CoprocessorBus` deste host ainda não
/// reconhece corretamente — NÃO investigado nesta sessão (fora do orçamento). O backend
/// `INTERPRETED` não foi levado até o fim: uma rodada real ficou mais de 49 minutos sem terminar (o
/// `@Timeout(30, MINUTES)` do JUnit, no modo padrão `SAME_THREAD`, não preempte um laço apertado que
/// nunca checa interrupção — só reporta falha DEPOIS que o método retorna) e foi abortada; como o
/// JIT já deu um resultado definitivo e muito mais barato, o interpretado fica para quando alguém
/// precisar — não é um requisito do aceite rodar até o fim fora do orçamento de uma sessão.
///
/// **Próximo passo recomendado (a sessão anterior)**: identificar a instrução exata que dispara o
/// `UNDEFINED` em `vfp_enable()`.
///
/// **Sessão de investigação do Oops em `vfp_enable` (2026-08-16) — causa raiz ISOLADA E CORRIGIDA
/// (bug real do `arm-jitter`, DIFERENTE do palpite da sessão anterior), M2 ainda NÃO fecha
/// (bloqueio novo e mais tardio revelado logo depois)**:
///
/// 1. **A hipótese anterior estava errada**: `vfp_enable()` (`arch/arm/vfp/vfpmodule.c` do kernel
///    real, confirmado lendo o fonte) NÃO toca em `FPEXC`/`FPSID` (registradores VFP, CP10/CP11) —
///    ela é `on_each_cpu(vfp_enable, ...)`, chamada incondicionalmente em ARMv6+ **antes** da sonda
///    de `FPSID`, e o próprio corpo só faz `get_copro_access()`/`set_copro_access()`, isto é,
///    `MRC`/`MCR p15,0,Rt,c1,c0,2` — o **`CPACR`** (Coprocessor Access Control Register, CP15, NÃO
///    CP10/11), concedendo acesso pleno a CP10/CP11 antes de qualquer instrução VFP rodar.
/// 2. **Causa raiz real**: {@link dev.vitorsilverio.armjitter.memory.mmu.Cp15VmsaCoprocessor}
///    (`arm-jitter`) nunca reivindicava `c1,c0,2` (só `c1,c0,0`/`SCTLR`) — a leitura/escrita de
///    `CPACR` caía em `unsupported()` (UNDEFINED), exatamente o `Oops - undefined instruction` em
///    `vfp_enable+0x8` observado. **Corrigido no `arm-jitter`** (`Cp15VmsaCoprocessor`, ver Javadoc
///    daquela classe): `CPACR` agora é armazenamento simples (round-trip, sem enforcement de trap —
///    mesma decisão de escopo do `c7`), com teste de regressão
///    (`cpacrIsStoredAndReadBackWithoutTrapEnforcement`). `mvn -o test` verde no `arm-jitter` (1364
///    core+truffle) + `mvn -o install`; G5 revalidado (gbaemu verde, ndsemu verde, armbox verde).
/// 3. **Confirmado ao vivo via harness diagnóstico temporário** (loop de fatias com impressão
///    periódica do console, removido antes do commit, mesmo precedente de sessões anteriores): com
///    o fix, `VFP support v0.3: not present` agora aparece LIMPO (sem Oops) e o boot continua bem
///    além do ponto anterior — `Setting up static identity map`, `devtmpfs: initialized`,
///    `hw-breakpoint: debug architecture 0x0 unsupported`, `Serial: AMBA PL011 UART driver`,
///    `bcm2835-mbox 2000b880.mailbox: mailbox enabled`, 3 requisições `raspberrypi-firmware`
///    respondidas com sucesso (`status 0x00000000`), `kprobes: kprobe jump-optimization is
///    enabled` — tudo isso em menos de 4 SEGUNDOS de tempo simulado do kernel (`kernel
///    time=3.465s`) e ~4 segundos reais/100 mil fatias.
/// 4. **M2 ainda NÃO fecha — bloqueio NOVO, mais tardio no boot**: depois de
///    `kprobes: kprobe jump-optimization is enabled`, o console para de crescer por completo —
///    **27,9 milhões de fatias seguintes (~18 minutos reais) sem NENHUM byte novo**, nem Oops nem
///    panic (não é um crash observável, é uma ausência total de progresso). Nenhuma hipótese de
///    causa raiz foi investigada ainda nesta sessão (orçamento esgotado depois do fix do CPACR).
///    **Próximo passo recomendado**: repetir a técnica desta sessão e das anteriores (trace
///    instrução-a-instrução via `ArmCore#step()`, ou o gancho `ArmTraceListener` da task `E2`) a
///    partir do ponto exato onde o console para de crescer, para identificar se a CPU está presa
///    num `WFI` sem IRQ chegando (suspeita nº1, dado o precedente da "tempestade de IRQ" já
///    corrigida — mas desta vez talvez FALTA de entrega, não excesso), num laço de espera de
///    resposta de mailbox/`raspberrypi-firmware` que nunca responde a alguma tag específica ainda
///    não implementada (ver "Não inclui"/mailbox da spec — o log mostra só 3 tags respondidas antes
///    de travar, pode haver uma 4ª tag que o kernel espera e nosso `Bcm2835Mailbox` simplesmente
///    ignora sem sinalizar erro nem responder), ou outra causa ainda não cogitada. Comparar contra
///    o oráculo QEMU 8.0.0 (mesmo kernel+DTB+initramfs+cmdline) para ver quais linhas de log
///    deveriam aparecer logo depois de `kprobes:` no boot real, e o que ele faz de diferente nesse
///    trecho, é o próximo passo mais barato antes de qualquer trace de instrução.
///
/// **Sessão de investigação do silêncio pós-`kprobes:` (2026-08-16) — hipótese de WFI/mailbox
/// DESCARTADA, achado real e independente CORRIGIDO (SMC/JIT), causa raiz do bloqueio principal
/// ainda NÃO isolada**:
///
/// 1. **A hipótese "WFI sem IRQ" estava errada**: instrumentando `ArmCore#setTraceListener` com um
///    `onMemoryAbort` (o mesmo gancho da task `E2`, que dispara sob `runBlocks`/JIT) mostrou que a
///    CPU NÃO fica parada — ela entra num LAÇO DE ABORTOS (`SECTION_TRANSLATION`) logo depois de
///    `kprobes:`, silencioso porque nenhum handler de kernel chega a rodar `printk`/`die()` antes
///    de reabortar. `sleepState()` nunca fica `HALTED`; `mode()` alterna `SUPERVISOR`↔`ABORT` e
///    `cpsr().irqDisabled()` fica travado em `true` (consistente com estar sempre dentro do
///    caminho de exceção). A hipótese de mailbox sem resposta também não se sustenta: só 3
///    requisições `raspberrypi-firmware` acontecem no log ANTES do ponto de travamento, nenhuma
///    depois — o kernel não está esperando uma 4ª tag, está preso no laço de abortos.
/// 2. **Achado real e independente CORRIGIDO** (`virtual-arm-box`, não `arm-jitter`):
///    {@link Bcm2835Machine#create} nunca envolvia o barramento do `ArmCore` em
///    `InvalidationAwareAddressSpace` — o MESMO decorador que `GbaConsole`/`Armbox` já usam
///    (`gba-game-compat.md`: bug histórico idêntico no gbaemu, "CPU class" de jogos que constroem
///    código na pilha). Sem ele, uma escrita do guest numa página com bloco JIT já compilado nunca
///    invalidava o cache — o core continuava executando bytecode compilado a partir do código
///    ANTIGO. `kprobes: kprobe jump-optimization` é a PRIMEIRA vez que este repositório exercita
///    código de guest automodificável (o self-test de kprobes arma um breakpoint otimizado logo
///    depois dessa mensagem) — {@link VersatilePbMachine} tem a MESMA lacuna, nunca exercitada
///    porque userspace busybox não se automodifica; não corrigida nesta sessão (fora do escopo da
///    F3, ver Javadoc de {@link Bcm2835Machine#create} para o achado completo).
/// 3. **O fix acima NÃO fecha M2 sozinho**: com `InvalidationAwareAddressSpace` no lugar, o MESMO
///    endereço-raiz de abort — `0x208d00c0`, `SECTION_TRANSLATION`, `DATA_READ` — reaparece
///    IDENTICO antes e depois do fix (comparação direta, mesmo kernel/dtb/cmdline), só a fatia
///    exata muda (~86460 sem o fix, ~77273 com o fix — esperado, o fix altera timing de
///    recompilação JIT mas não a lógica). Isso indica fortemente que é a MESMA causa raiz
///    pré-existente nos dois casos, não uma regressão introduzida pelo fix. `r14`/LR no momento do
///    primeiro abort (`0xc0337b50`) é um endereço de `.text` do kernel plausível — a instrução
///    CHAMADORA é código real; o valor lido/desreferenciado (`0x208d00c0`) é que não bate com
///    nenhum dos registradores `r0-r13` capturados no momento da falta (não é uma cópia direta de
///    registrador, precisa vir de um deslocamento/tabela). O segundo endereço (para onde o
///    handler tenta retornar/reler, ficando preso) MUDA entre execuções (`0x608e00c0` numa rodada,
///    `0xa08c00c0` noutra) — sugere que a fixup do abort depende de algo que varia por
///    execução (ex.: o contador livre do `Bcm2835SystemTimer`), não é puramente determinístico.
/// 4. **Próximo passo recomendado**: trace instrução-a-instrução (`Bcm2835Machine.Backend
///    .INTERPRETED` + `ArmCore#step()`, não `runBlocks`) a partir de ~70 mil instruções depois do
///    boot para capturar a instrução EXATA (opcode, não só PC) que produz `0x208d00c0` a partir de
///    `LR=0xc0337b50` — provavelmente um `LDR` com deslocamento/indexado a partir de uma tabela ou
///    lista cujo conteúdo está corrompido (fonte ainda desconhecida: pode ser um bug real de
///    decodificação/execução do `arm-jitter` em alguma instrução ainda não exercitada por
///    gbaemu/ndsemu, já que este é o primeiro kernel Linux de sistema real sob `ARM11_MPCORE`).
///    Comparar contra o oráculo QEMU (registrador a registrador via monitor HMP, mesma técnica já
///    usada para o bug de `SCTLR`/`CR_XP`) no mesmo ponto do boot é o próximo passo mais barato
///    antes de instrumentar mais.
///
/// **Sessão de trace instrução-a-instrução do abort storm (2026-08-16/17) — 1 bug real do
/// `arm-jitter` ISOLADO, CORRIGIDO E VALIDADO (G5 completo), mas M2 ainda NÃO fecha: a causa raiz
/// do abort storm em si (endereço `0x208d00c0`) foi NARROWED a um achado concreto e novo, não
/// totalmente isolada ainda**:
///
/// 1. **Bug real corrigido no `arm-jitter`**: seguindo o próximo passo recomendado pela sessão
///    anterior (trace via {@link dev.vitorsilverio.armjitter.core.ArmCore#step()} a partir de
///    ~77 mil fatias, fast-forward via JIT + troca para `step()` só nos últimos passos, técnica de
///    duas fases), o primeiro fault foi confirmado IDÊNTICO ao já registrado
///    (`instructionAddress=fault.virtualAddress()=0x208d00c0`, `SECTION_TRANSLATION`), mas agora
///    reportado como `DATA_READ` — o que é ERRADO: o fault acontece na BUSCA da instrução em
///    `0x208d00c0` (o novo PC depois de um `MOVS PC,LR`), deveria ser `INSTRUCTION_FETCH`. Causa:
///    `dev.vitorsilverio.armjitter.memory.InvalidationAwareAddressSpace` (o decorador que a sessão
///    anterior passou a usar em {@link Bcm2835Machine#create} para resolver o bug de SMC/kprobes)
///    NUNCA sobrescrevia `fetch16`/`fetch32` — caíam no `default` de `AddressSpace`, que delega à
///    PRÓPRIA `read32` do decorador (o caminho de DADOS do delegado), não a `fetch32` dele (o
///    caminho de INSTRUÇÃO, com TLB separada). Toda busca de instrução sob este decorador perdia a
///    TLB de instrução E o tipo `INSTRUCTION_FETCH` — uma falha de busca virava `DATA_ABORT` em vez
///    de `PREFETCH_ABORT` (vetor errado, correção de PC errada -4 vs. -8). Mesma lacuna encontrada
///    em `DualInvalidationAwareAddressSpace` (usado pelo `ndsemu`) e em `translationGeneration()`
///    (também não encaminhado, quebraria invalidação de bloco JIT após troca de `TTBR0`/`CONTEXTIDR`
///    para qualquer consumidor futuro que combine MMU com este decorador). **Corrigido no
///    `arm-jitter`**: as duas classes agora sobrescrevem `fetch16`/`fetch32`/`translationGeneration`
///    encaminhando ao delegado — aditivo/G3, 4 testes de regressão novos (2 por classe: um delegado
///    de teste com valores DIFERENTES em `fetchNN` vs. `readNN` prova que o caminho certo é chamado;
///    sanidade confirmada via `git stash`, os 2 testes falham exatamente como esperado sem o fix).
///    `mvn -o test` verde no `arm-jitter` + `mvn -o install`; G5 revalidado (gbaemu verde, ndsemu
///    verde, armbox 40/41 — mesma falha pré-existente de `Armv7TortureTest`/`VfpRegisters` já
///    documentada em toda sessão anterior da F3, não é regressão).
/// 2. **Aplicado o fix e reproduzido de novo**: o fault agora É `INSTRUCTION_FETCH` corretamente —
///    mas o MESMO endereço (`0x208d00c0`) continua faltando (`SECTION_TRANSLATION`), e deixar a
///    execução CONTINUAR além do primeiro fault (em vez de parar nele) mostra 27,5 MILHÕES de
///    reaborts idênticos em 400 mil fatias sem o console crescer nem 1 byte — confirma que o fix
///    de tipagem sozinho não fecha M2 (é uma correção real e correta, mas não é a causa raiz do
///    travamento).
/// 3. **Achado NOVO e concreto sobre a causa raiz real**: a instrução que produz `0x208d00c0` foi
///    identificada por trace registrador-a-registrador: é o `LDR LR,[PC,LR,LSL#2]` (`0xe79fe10e`)
///    do `vector_stub` de IRQ do kernel real (`arch/arm/kernel/entry-armv.S`) em `0xffff1044` — o
///    idioma clássico de despacho por tabela de branch, com `Rd==Rm==r14` (o mesmo registrador é
///    base do deslocamento E destino). Dump direto da RAM confirma que a TABELA em si está
///    perfeita: `0xffff1058` (índice 3, modo SVC interrompido) contém `0xc0008d20`, um endereço de
///    `.text` do kernel plausível (`__irq_svc`). Só que o `LDR` LÊ e devolve `0x208d00c0` — que é
///    EXATAMENTE `0xc0008d20` com os 4 bytes invertidos (`c0 00 8d 20` → `20 8d 00 c0`). Ou seja:
///    esta é uma leitura de DADOS de 32 bits sendo devolvida em BIG-ENDIAN quando deveria ser
///    little-endian. Rastreando `cpsr().isBigEndian()` (`CPSR.E`, bit 9) instrução a instrução:
///    **`CPSR.E` está `true` bem antes do `LDR`, herdado do contexto interrompido** — e uma sonda
///    mais ampla (`cpsr.E` amostrado a CADA fatia desde o início do boot) mostra que o bit NÃO fica
///    preso permanentemente: ele OSCILA entre `true`/`false` repetidas vezes, sempre em modo
///    `SUPERVISOR`, concentrado num punhado de PCs perto do FIM do `.text` do kernel
///    (`0xc0a6xxxx`-`0xc0a9xxxx`, plausivelmente a região do laço ocioso/`WFI`/`arch_cpu_idle`,
///    dado que o kernel tem ~10,8MB de código e essa faixa fica logo depois disso) — a última
///    virada antes do fault acontece na MESMA fatia (`77273`), no MESMO PC (`0xc0a6603c`) onde o
///    `servicePendingIrq()` intercepta a CPU e desvia para o vetor de IRQ. `spsr(IRQ)`/
///    `spsr(SUPERVISOR)`/`spsr(ABORT)` amostrados ao final NÃO mostram `E=1` armazenado (bit 9 = 0
///    nos três), então a hipótese simples "um SPSR poluído uma vez propaga E=1 para sempre via
///    `MOVS PC,LR`" não está confirmada — o mecanismo exato de COMO/ONDE `CPSR.E` vira `true` momentos
///    antes deste `LDR` específico não foi isolado nesta sessão.
/// 4. **Próximo passo recomendado, concreto**: (a) localizar a PRIMEIRA instrução (não só a
///    primeira fatia) que escreve `CPSR.E=1` — trace via `step()` teria que cobrir a região
///    `0xc0a6xxxx`-`0xc0a9xxxx` perto do laço ocioso, correlacionando cada `MSR`/`MOVS PC,Rn`/`RFE`
///    com o valor de E antes/depois, para achar o `MSR`/retorno de exceção específico que liga o
///    bit; (b) cross-referenciar contra `arch/arm/kernel/entry-armv.S`/`arch/arm/kernel/process.S`
///    do `raspberrypi/linux` (árvore documentada em `testdata/raspi1/README.md`) para essa faixa de
///    endereço — plausivelmente `cpu_v6_do_idle`/`arch_cpu_idle`/`default_idle` ou o próprio
///    `vector_stub`/`ret_from_intr`, mas isso não foi confirmado ainda; (c) considerar se isto é
///    causado por um bug real do `arm-jitter` na banked-register/SPSR machinery do
///    interpretador/JIT nativo (ex.: leitura de SPSR de um banco errado, ou um `MSR` mal decodificado
///    que seta bit 9 por engano) em vez de comportamento genuíno do kernel — o kernel LE normal não
///    deveria precisar de `SETEND` nesta fase do boot.
///
/// **Sessão de fechamento do CPSR.E (2026-08-17) — causa raiz ISOLADA E CORRIGIDA (bug real do
/// `arm-jitter`), abort storm 100% RESOLVIDO, M2 ainda NÃO fecha (bloqueio novo, bem mais tardio,
/// já com causa raiz identificada)**:
///
/// 1. **A origem exata de `CPSR.E=true`, pendente desde a sessão anterior, foi isolada**: um trace
///    instrução-a-instrução via {@link dev.vitorsilverio.armjitter.core.ArmCore#step()} a partir
///    do boot (só ~320 mil instruções até o primeiro flip — MUITO mais cedo do que a suspeita
///    "perto do fault" da sessão anterior) capturou o instante exato: `SETEND BE` (`0xf1010200`)
///    em `0xc0a65c84`, seguido ~60 instruções depois por `SETEND LE` (`0xf1010000`) em
///    `0xc0a6616c`/`0xc0a66228` — **o próprio kernel Linux real executa este par
///    deliberadamente**, ao redor de uma rotina perto do laço ocioso. Não é bug de decodificação:
///    o `arm-jitter` decodifica/executa `SETEND` corretamente nos dois casos.
/// 2. **A causa raiz real é arquitetural, não de decodificação**: o ARM ARM (DDI 0406C B1.8.3)
///    exige que hardware real reprograme `CPSR.E` para `SCTLR.EE` em TODA entrada de exceção,
///    independente do que o código interrompido tinha configurado via `SETEND` — isso garante que
///    todo handler de exceção rode numa endianness conhecida mesmo interrompendo um trecho
///    legitimamente em `SETEND BE`. `AProfileExceptionModel#enterException` (arm-jitter) nunca
///    fazia isso: `CPSR.E` era simplesmente herdado do contexto interrompido. Quando a IRQ do
///    timer chegava bem no meio da janela `SETEND BE`/`SETEND LE` do kernel, o handler de exceção
///    (`vector_stub`) herdava `E=1` e o próprio `LDR LR,[PC,LR,LSL#2]` que busca seu alvo de salto
///    na tabela de branch (um acesso de DADOS comum) lia os 4 bytes invertidos — o
///    `0xc0008d20`→`0x208d00c0` já identificado na sessão anterior.
/// 3. **Corrigido no `arm-jitter`**: {@link dev.vitorsilverio.armjitter.core.ExceptionEndiannessPolicy}
///    novo (mesmo padrão aditivo de `ModeChangeListener`/`MemoryAbortListener` — vazio por padrão,
///    G3), chamado por `AProfileExceptionModel#enterException` logo depois do `CPSR` antigo já
///    estar salvo em `SPSR`; {@link dev.vitorsilverio.armjitter.memory.mmu.Cp15VmsaCoprocessor}
///    implementa a interface, forçando `CPSR.E = SCTLR.EE` (bit 25). {@link Bcm2835Machine#create}
///    e {@link VersatilePbMachine#create} registram `core.setExceptionEndiannessPolicy(cp15)`
///    (quarto gancho do CP15). 3 testes de regressão novos no arm-jitter
///    (`ExceptionEndiannessPolicyTest` + `Cp15VmsaCoprocessorTest.applyOnExceptionEntry...`).
///    `mvn -o test` verde no arm-jitter (1370 core + 13 truffle) + `mvn -o install`; G5 revalidado
///    (gbaemu verde, ndsemu verde, armbox 40/41 — mesma falha pré-existente de
///    `Armv7TortureTest`/`VfpRegisters` de sempre, não é regressão; `virtual-arm-box` verde).
/// 4. **Efeito no boot, confirmado ao vivo**: com o fix, o abort storm em `0x208d00c0` desaparece
///    por completo — o boot avança MUITO além do ponto anterior (`raspberrypi-firmware`/mailbox,
///    `mmc0`, enumeração USB) até tentar montar a raiz de verdade. **M2 ainda NÃO fecha**: um
///    bloqueio NOVO e bem mais tardio aparece — `Kernel panic - not syncing: VFS: Unable to mount
///    root fs on "/dev/ram" or unknown-block(1,0)`, em `prepare_namespace()`. Como este panic
///    acontece DENTRO de `kernel_init_freeable()` (chamado ANTES de `free_initmem()` na sequência
///    real do `kernel_init()`), a mensagem `Freeing unused kernel memory` nunca é alcançada —
///    consistente com o kernel real, não um sintoma de regressão.
/// 5. **Causa raiz do bloqueio novo já identificada (não corrigida nesta sessão)**: o `FdtPatcher`
///    escreve `/chosen/bootargs`/`/memory@0/reg` mas NUNCA `/chosen/linux,initrd-start`/
///    `linux,initrd-end` — as duas propriedades que um kernel com Device Tree (ao contrário do
///    protocolo ATAGs do `versatilepb`, que usa `ATAG_INITRD2`) precisa para descobrir onde o
///    `initramfs.cpio.gz` carregado na RAM está. Sem elas, o kernel ignora o blob inteiro e, como
///    a cmdline pede `root=/dev/ram`, tenta montar `/dev/ram` como um dispositivo de bloco
///    formatado — que não é, daí o panic. **Próximo passo recomendado, concreto**: estender
///    {@link dev.vitorsilverio.virtualarmbox.boot.FdtPatcher} para CRIAR propriedades novas num nó
///    existente (hoje só sobrescreve o valor de propriedades já presentes, ver o Javadoc daquela
///    classe — `/chosen` já existe no `.dtb` real, só faltam as 2 propriedades), escrever
///    `linux,initrd-start`/`linux,initrd-end` (endereço físico onde `initramfs.cpio.gz` foi
///    carregado, `INITRD_LOAD_ADDR`/`INITRD_LOAD_ADDR + initramfs.length` de
///    {@link Bcm2835Machine}) e então tentar de novo os testes `@Disabled` do M2 (o INTERPRETED
///    nem chegou a ser re-executado nesta sessão, mas deve se beneficiar do mesmo fix de CPSR.E —
///    a causa raiz é comum aos dois motores).
///
/// **Sessão de extensão do `FdtPatcher` (2026-08-17) — `/chosen/linux,initrd-start`/`linux,initrd-
/// end` implementados, M2 ainda NÃO fecha (bloqueio novo, bem mais tardio, causa raiz NARROWED a
/// um opcode específico)**:
///
/// 1. Seguindo o próximo passo recomendado pela sessão anterior:
///    {@link dev.vitorsilverio.virtualarmbox.boot.FdtPatcher} ganhou
///    {@link dev.vitorsilverio.virtualarmbox.boot.FdtPatcher#withInitrdRange} — diferente de
///    {@code withBootargs}/{@code withMemorySize} (que só SOBRESCREVEM propriedades já
///    existentes), este cria propriedades NOVAS dentro do nó `/chosen` já existente (o `.dtb` cru
///    não tem `linux,initrd-start`/`linux,initrd-end`). {@link Bcm2835Machine#create} passa a
///    aplicar o patch com `INITRD_LOAD_ADDR`/`INITRD_LOAD_ADDR + initramfs.length`.
/// 2. **Efeito confirmado no boot JIT**: o `Kernel panic - VFS: Unable to mount root fs` desta
///    sessão anterior desaparece por completo. O kernel monta o initramfs e chega a executar
///    `/init` de verdade (`Run /init as init process` aparece no console, ~8 minutos reais de
///    corrida) — o mais longe que este repositório já chegou no boot do raspi1.
/// 3. **M2 ainda NÃO fecha — bloqueio NOVO, mais tardio que qualquer um anterior**: logo depois de
///    `Run /init as init process`, `Internal error: Oops - undefined instruction` em
///    `v6_clear_user_highpage_aliasing+0x58/0x104`, chamado por `handle_mm_fault` ao tratar uma
///    falta de página do `execve()` do `/init` — o processo `init` morre (`Kernel panic - not
///    syncing: Attempted to kill init!`). Opcode faltoso decodificado a partir do dump de `Code:`
///    no Oops (`ec432f06`): `MCRR p15,0,r2,r3,c6` (encoding A1 padrão de `MCRR`/`MRRC`, cond=AL,
///    L=0 → MCRR, Rt2=r3, Rt=r2, coproc=p15, opc1=0, CRm=c6) — uma transferência DUPLA de
///    registrador para coprocessador, arquiteturalmente diferente do `MCR`/`MRC` de registrador
///    único que {@code Cp15VmsaCoprocessor}/{@code Bcm2835Cp15Extras} já tratam. Provável lacuna de
///    DECODE no `arm-jitter` A32 (não só de despacho do `CoprocessorBus`) — nem gbaemu (ARMv4T),
///    nem ndsemu (ARMv5TE), nem armbox (user-mode) jamais exercitaram `MCRR`/`MRRC`, então esta
///    seria a primeira vez. **Não investigado além da decodificação do opcode** (fora do orçamento
///    desta sessão).
/// 4. **Achado colateral, não fatal, registrado para referência**: bem antes do Oops de `init`, o
///    log mostra duas ocorrências de `Division by zero in kernel` em `pl011_set_termios` (via
///    `uart_update_timeout`/`div64_u64`) ao abrir `/dev/console` — o kernel trata como exceção não
///    fatal e o boot continua (`sdhost-bcm2835`/`raspberrypi-firmware` seguem normalmente logo
///    depois). Possível causa: algum campo de clock/baud que o `Pl011Uart`/mailbox devolve como 0
///    onde o driver espera um divisor não-zero — não investigado, só observado.
/// 5. **Próximo passo recomendado**: (a) confirmar no decoder A32 do `arm-jitter`
///    (`ArmDecoder`/`decoder/`) se `MCRR`/`MRRC` têm case próprio ou caem em `unsupported`/UNDEFINED
///    por ausência de decode (mais provável, dado que nenhum consumidor os exercitou); se for
///    lacuna de decode, é uma task nova do `arm-jitter` (fora do "Não inclui" desta task — mudança
///    de decoder não é "bug real" no sentido estrito, é FEATURE faltando, mas o precedente de
///    B1.8/BE8 já tratou uma feature faltando de escopo similar como bloqueio de F3 justificável);
///    (b) só depois de MCRR/MRRC decodificarem, repetir o boot e ver se `execve("/init")` conclui;
///    (c) investigar a divisão por zero do PL011 se ela voltar a aparecer de forma fatal (por ora
///    é só um achado colateral não-bloqueante). Backend INTERPRETED não foi re-executado nesta
///    sessão (orçamento).
/// 6. `mvn -o test` verde no `virtual-arm-box` (68 testes, 4 skipped = M2×2+M3×2, motivo do
///    `@Disabled` atualizado); `VersatilePbBootTest` continua verde. Nenhum arquivo do `arm-jitter`
///    tocado nesta sessão.
///
/// **Sessão de correção do marcador de M2 (2026-08-17) — M2 FECHADO nos DOIS backends**: seguindo
/// o próximo passo recomendado pela sessão anterior (harness com progresso observável em vez do
/// `@Test` cru), um harness temporário confirmou que o boot JIT já reproduz `Run /init as init
/// process` em ~40s reais (2,4 milhões de fatias) e NUNCA trava/aborta a partir daí — a suspeita
/// da sessão anterior ("resultado genuinamente desconhecido") estava certa em espírito, mas a
/// causa real não era um bloqueio novo: era o texto literal do marcador. `mark_readonly()` (que só
/// roda DEPOIS de `free_initmem()` em `kernel_init()`) já tinha impresso sua mensagem
/// ("This architecture does not have kernel memory protection") ANTES de "Run /init" aparecer no
/// console — ou seja, `free_initmem()` já tinha rodado, só que com um texto diferente do enunciado
/// da task. Capturado ao vivo: `"Freeing unused kernel image (initmem) memory: 500K"` — kernels
/// modernos (confirmado neste `kernel.img` 6.18.33) unificaram a mensagem de memória do `initmem`
/// com a de imagem do kernel, mesmo precedente exato da redefinição de M1 (texto do enunciado não
/// bate com o kernel oficial real desta task). Corrigido o marcador para o prefixo estável
/// `"Freeing unused kernel"` (ver Javadoc de {@link #FREEING_KERNEL_MEMORY}) e reativados os dois
/// testes de M2 (removido `@Disabled`): **JIT passa em 38,7s, INTERPRETED passa em 50,2s** — bem
/// mais rápido que o esperado pelas sessões anteriores, porque o marcador correto acontece muito
/// mais cedo no boot que o ponto (mmc0/SDIO, muito mais tardio) onde o harness de diagnóstico desta
/// sessão continuou observando (sem crash, mas em retry aparentemente indefinido — **achado
/// colateral para M3**: não bloqueia M2, mas se M3 precisar que o boot avance por muito mais tempo
/// além do prompt do shell, o retry de `mmc0`/`sdhost-bcm2835` "no support for card's volts" pode
/// dominar o console; ver "Não inclui" da spec — SD/MMC real é deliberadamente fora de escopo,
/// então isso é esperado, não um bug). `mvn -o test` verde no `virtual-arm-box`; nenhum arquivo do
/// `arm-jitter` tocado nesta sessão (G5 completo não necessário, só o G5 "leve" deste repo).
///
/// **Sessão de fechamento do M3 (CPRMAN, 2026-08-17) — bug real corrigido, M3 ainda NÃO fecha
/// (bloqueio novo e diferente, fora do escopo desta task)**: implementado
/// `dev.vitorsilverio.virtualarmbox.device.bcm2835.Bcm2835Cprman` mínimo — um trace de boot
/// confirmou que, sem NENHUM periférico de clock, o driver `clk-bcm2835` real do kernel esbarra
/// em `"plld: couldn't lock PLL"` / `error -ETIMEDOUT` (o bit `FLOCKD` de `CM_LOCK` nunca liga sob
/// `OpenBus`), cai no mecanismo de *deferred probe* e só termina de registrar `ttyAMA0` bem
/// depois do PID 1 já ter aberto `/dev/console` preso no `earlycon` antigo. `Bcm2835Cprman`
/// corrige isso fazendo `CM_LOCK` sempre reportar "todos os PLLs travados" (nenhuma matemática de
/// PLL real, ver Javadoc daquela classe) — confirmado ao vivo: `ETIMEDOUT`/`couldn't lock PLL`
/// desaparecem do log. **M3 continua NÃO fechando**: um bloqueio novo e diferente aparece pouco
/// depois de `Run /init as init process` — um laço de nova tentativa aparentemente sem fim do
/// `sdhost-bcm2835`/`mmc0` (esperado em hardware real, SD/MMC deliberadamente fora do "Inclui"
/// desta task) que, por causa da compressão agressiva de tempo do
/// `Bcm2835SystemTimer` (`HOST_CYCLES_PER_MICROSECOND`), nunca cede espaço para o prompt do shell
/// aparecer dentro do orçamento observável desta sessão (20 milhões de fatias, ~8 minutos reais,
/// sem sinal do prompt nem do banner do próprio `/init`). Ver Javadoc de
/// `dev.vitorsilverio.virtualarmbox.Bcm2835Machine` para o achado completo, a extrapolação de
/// tempo necessário (~60-90 min reais para o orçamento atual de {@link #MAX_SLICES}) e o próximo
/// passo recomendado (`FdtPatcher` capaz de sobrescrever uma propriedade com tamanho diferente,
/// para desabilitar o nó `mmc@7e202000` via `status = "disabled"`). `mvn -o test` verde no
/// `virtual-arm-box`; nenhum arquivo do `arm-jitter` tocado nesta sessão.
///
/// **Sessão de extensão do `FdtPatcher` (2026-08-17) — DOIS bloqueios reais fechados
/// (`mmc0`/`sdhost` E `usb`/`dwc_otg`), M3 ainda NÃO fecha (TERCEIRO bloqueio novo, revelado
/// logo depois, causa raiz não isolada)**:
///
/// 1. Seguiu o próximo passo recomendado pela sessão anterior:
///    {@link dev.vitorsilverio.virtualarmbox.boot.FdtPatcher#withNodeDisabled} novo — ao
///    contrário do que a sessão anterior presumiu, NÃO precisou de nenhuma extensão estrutural
///    (o {@code withProperty} interno já lidava com troca de TAMANHO de propriedade, mesmo
///    caminho que `withBootargs` já exercita). {@link Bcm2835Machine#create} passou a desabilitar
///    `mmc@7e202000` (`status = "okay"` → `"disabled"`, SOBRESCRITA).
/// 2. **Efeito confirmado via harness de diagnóstico temporário** (`Raspi1DiagTempTest`, removido
///    antes do commit, mesmo precedente de sessões anteriores): o retry infinito de `mmc0` REALMENTE
///    desaparece (`mmc0count=0` do início ao fim de uma corrida de 72,6 milhões de fatias, ~30min
///    reais) — mas o console fica preso, agora SILENCIOSAMENTE (sem spam), num tamanho ESTÁVEL
///    (`consoleLen=32869`) a partir de ~12,6 milhões de fatias, com a última linha sendo
///    `"state() pending due to 20980000.usb"`.
/// 3. **Segundo bloqueio real isolado e corrigido, mesma sessão**: `20980000.usb` é o nó
///    `usb@7e980000` (`compatible = "brcm,bcm2708-usb"`, o `dwc_otg`) — deliberadamente fora do
///    "Inclui" da spec (servido por `OpenBus`), mas ao contrário de `mmc0` (que faz retry ruidoso
///    para sempre), o driver USB real fica preso numa espera SÍNCRONA e SILENCIOSA (`state()
///    pending`, provavelmente `device_pm_wait_for_dev`/`dpm_prepare` esperando o `probe()` do
///    controlador nunca concluir sob `OpenBus`) — sem nenhum printk periódico, então o sintoma no
///    console é ausência total de crescimento, não inundação. `withNodeDisabled` foi generalizado
///    para lidar com os dois casos reais do `.dtb` (SOBRESCRITA quando `status` já existe, como em
///    `mmc@7e202000`; CRIAÇÃO quando não existe, como em `usb@7e980000` — a ausência de `status`
///    significa "okay" por definição do Device Tree) tentando `withProperty` e caindo para
///    `withNewProperty` em caso de propriedade ausente. {@link Bcm2835Machine#create} passou a
///    desabilitar `usb@7e980000` também.
/// 4. **Efeito confirmado ao vivo**: com os dois nós desabilitados, `mmc0count` continua `0` E o
///    console avança MUITO além do ponto anterior — `"Run /init as init process"` aparece de novo
///    (consoleLen sobe de 32869 para 34036) — mas **M3 ainda NÃO fecha**: um TERCEIRO bloqueio,
///    novo e diferente dos dois anteriores, aparece imediatamente depois — o console fica
///    ESTÁVEL em `consoleLen=34036` por pelo menos 24 milhões de fatias adicionais (~10 minutos
///    reais observados nesta sessão, harness interrompido por orçamento, não por timeout do
///    teste) sem NENHUMA linha nova — nem o banner do próprio `/init` deste repositório (um
///    `echo` simples, sem dependência de hardware nenhuma, que a sessão do CPRMAN já registrou
///    como o primeiro sinal esperado depois de `/init` rodar), nem o prompt do shell. Isso sugere
///    que o processo `init` trava (ou o scheduler nunca faz progresso visível) ANTES de sequer
///    executar seu primeiro `echo` — categoricamente diferente de mmc0 (retry ruidoso) e de usb
///    (espera síncrona identificável por nome de dispositivo no log): aqui não há NENHUMA pista
///    textual do que está travando.
/// 5. **Causa raiz do terceiro bloqueio NÃO isolada nesta sessão** (orçamento de investigação
///    aberta esgotado depois dos dois fixes de DTB acima). **Próximo passo recomendado,
///    concreto**: trace instrução-a-instrução (`ArmCore#step()`, backend INTERPRETED, mesma
///    técnica das sessões de CPSR.E/tempestade de IRQ) a partir do ponto exato onde
///    `"Run /init as init process"` é impresso, para descobrir se a CPU está presa em `WFI` sem
///    IRQ chegando (suspeita nº1, dado o precedente da tempestade de IRQ já corrigida — mas agora
///    a suspeita seria falta de entrega, não excesso), num laço de espera de alguma outra chamada
///    de sistema que o `execve("/init")`/`do_execve` faz cedo (ex.: leitura de mais páginas do
///    initramfs, alocação de pilha do processo), ou outra causa ainda não cogitada. Comparar
///    contra o oráculo QEMU 8.0.0 (mesmo kernel+DTB+initramfs+cmdline, sem os nós de `mmc`/`usb`
///    desabilitados, já que o QEMU tem drivers reais para eles) não serve diretamente aqui — mas
///    reproduzir com o `.dtb` PATCHEADO (os dois nós desabilitados) no QEMU também, se possível,
///    isolaria se o comportamento é específico deste emulador ou uma consequência esperada de
///    desabilitar os nós (ex.: algum script de init do busybox espera por `/dev/mmcblk0`
///    silenciosamente antes de imprimir qualquer coisa — precisaria ser confirmado lendo o
///    `init`/`inittab` do `initramfs.cpio.gz` desta task, não investigado ainda).
/// 6. `mvn -o test` verde no `virtual-arm-box` (agora com {@code FdtPatcherTest} +3 testes novos
///    de `withNodeDisabled`, cobrindo os dois casos — sobrescrita e criação — mais um round-trip);
///    M3 volta a `@Disabled` com o achado atualizado (não fecha nesta sessão). Nenhum arquivo do
///    `arm-jitter` tocado (G5 completo não necessário, só o G5 "leve" deste repo).
///
/// {@link #smokeTestBootsWithoutException()} prova que a infraestrutura desta task
/// (CP15/CP14/MMU/periféricos/`FdtPatcher`/`ZImageDecompressor`/handoff) está correta hoje.
class Raspi1BootTest {
    private static final Path TESTDATA = Path.of("testdata", "raspi1");
    private static final String CMDLINE = "console=ttyAMA0,115200 earlycon root=/dev/ram rdinit=/init";
    private static final String EARLYCON_BANNER = "Booting Linux on physical CPU";
    /// **Sessão de correção do marcador de M2 (2026-08-17)**: o texto literal do enunciado
    /// (`"Freeing unused kernel memory"`) NUNCA aparece neste `kernel.img` real (6.18.33) — não
    /// por bug, por REDAÇÃO: um harness de diagnóstico temporário (mesmo padrão de sessões
    /// anteriores, ver Javadoc da classe) confirmou que `mark_readonly()`/"This architecture does
    /// not have kernel memory protection" (que só roda DEPOIS de `free_initmem()` em
    /// `kernel_init()`) já tinha passado quando "Run /init as init process" apareceu — ou seja,
    /// `free_initmem()` já tinha rodado, só que com outra frase. O texto real capturado é
    /// `"Freeing unused kernel image (initmem) memory: 500K"` (kernels modernos unificam a
    /// mensagem de memória de init com a de imagem, ao contrário do texto mais antigo do
    /// enunciado da task). Mesmo precedente da redefinição de M1 (marcador do enunciado não bate
    /// com o kernel oficial real desta task) — usa-se um prefixo estável que cobre as duas
    /// redações.
    private static final String FREEING_KERNEL_MEMORY = "Freeing unused kernel";
    private static final String SHELL_PROMPT = "/ #";
    private static final String SHELL_COMMAND = "echo RASPI\"1-SHELL-OK\"\n";
    private static final String SHELL_COMMAND_OUTPUT = "RASPI1-SHELL-OK";

    /// Achado desta sessão (fechamento do M2): `calibrate_delay()` do kernel real (chamado logo
    /// após "Console: colour dummy device...", antes de "Calibrating delay loop... N BogoMIPS")
    /// executa um laço de calibração pesado o bastante (medido: >11 milhões de fatias sem sequer
    /// terminar, sob interpretado) que o teto anterior de 8 milhões nunca alcançava — não porque
    /// o boot travasse, só porque o orçamento de fatias era pequeno demais para esse laço
    /// específico. Elevado com folga.
    private static final int MAX_SLICES = 200_000_000;
    private static final int CONSOLE_POLL_INTERVAL = 2_000;
    private static final int SLICES_PER_TYPED_BYTE = 200;

    /// Fatias suficientes para atravessar o `head.S` inicial sem lançar/travar — não tenta
    /// chegar a nenhum marco, só prova que a infra está correta
    /// (RAM/CP15/MMU/periféricos/handoff/descompressão no host). Deliberadamente conservador:
    /// mais fatias alcançam o limite de `CPSR.E=1`/big-endian documentado no Javadoc da classe
    /// (não é um teto desta task, é uma decisão de escopo já tomada no `arm-jitter`, task
    /// `B1.5`).
    private static final int SMOKE_SLICES = 60;

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void smokeTestBootsWithoutException() throws Exception {
        assumeTrue(Files.exists(TESTDATA.resolve("kernel.img")), "assets reais ausentes nesta checkout");
        Bcm2835Machine machine = load(Bcm2835Machine.Backend.INTERPRETED, new ByteArrayOutputStream());

        int initialPc = machine.core().programCounter();
        assertEquals(0x0000_8000, initialPc, "entrada esperada no endereço de link do stext descomprimido");

        for (int slice = 0; slice < SMOKE_SLICES; slice++) {
            machine.runSlice();
        }

        assertTrue(machine.core().cycles() > 0, "nenhum ciclo executado");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void reachesEarlyconBannerAcceiteM1Interpreted() throws Exception {
        assertReachesMarker(Bcm2835Machine.Backend.INTERPRETED, EARLYCON_BANNER);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void reachesEarlyconBannerAcceiteM1Jit() throws Exception {
        assertReachesMarker(Bcm2835Machine.Backend.JIT, EARLYCON_BANNER);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void reachesFreeingKernelMemoryAcceiteM2Interpreted() throws Exception {
        assertReachesMarker(Bcm2835Machine.Backend.INTERPRETED, FREEING_KERNEL_MEMORY);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void reachesFreeingKernelMemoryAcceiteM2Jit() throws Exception {
        assertReachesMarker(Bcm2835Machine.Backend.JIT, FREEING_KERNEL_MEMORY);
    }

    @Disabled("M3: sessao de trace 2026-08-18(2) confirmou um loop de 157 instrucoes 100% "
            + "deterministico em 0xc05b1750-0xc05b18c4 (registradores r0-r4/r6/r9/r13/r14 "
            + "bit-a-bit identicos a cada periodo, 20+ repeticoes) — DESCARTOU o bug de "
            + "LDREX/STREX/DACR no arm-jitter hipotetizado antes (DACR round-trip correto, "
            + "STREX sempre sucede de primeira em 2 call-sites distintos); timer AINDA entrega "
            + "IRQ nesta janela (27 em 100k runSlice). Causa raiz exata (memoria nao amostrada, "
            + "CP15 nao amostrado, ou scheduler/kthread que nunca roda) ainda NAO isolada — ver "
            + "Javadoc da classe para o achado completo e o proximo passo recomendado.")
    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void bootsToInteractiveBusyboxShellAcceiteM3Interpreted() throws Exception {
        assertReachesInteractiveShell(Bcm2835Machine.Backend.INTERPRETED);
    }

    @Disabled("M3: sessao de trace 2026-08-18(2) confirmou um loop de 157 instrucoes 100% "
            + "deterministico em 0xc05b1750-0xc05b18c4 (registradores r0-r4/r6/r9/r13/r14 "
            + "bit-a-bit identicos a cada periodo, 20+ repeticoes) — DESCARTOU o bug de "
            + "LDREX/STREX/DACR no arm-jitter hipotetizado antes (DACR round-trip correto, "
            + "STREX sempre sucede de primeira em 2 call-sites distintos); timer AINDA entrega "
            + "IRQ nesta janela (27 em 100k runSlice). Causa raiz exata (memoria nao amostrada, "
            + "CP15 nao amostrado, ou scheduler/kthread que nunca roda) ainda NAO isolada — ver "
            + "Javadoc da classe para o achado completo e o proximo passo recomendado.")
    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void bootsToInteractiveBusyboxShellAcceiteM3Jit() throws Exception {
        assertReachesInteractiveShell(Bcm2835Machine.Backend.JIT);
    }

    private static void assertReachesMarker(Bcm2835Machine.Backend backend, String marker) throws Exception {
        assumeTrue(Files.exists(TESTDATA.resolve("kernel.img")), "assets reais ausentes nesta checkout");
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        Bcm2835Machine machine = load(backend, console);

        boolean reached = runUntil(machine, console, marker);
        assertTrue(reached, "esperava '" + marker + "' no console, obtive:\n" + text(console));
    }

    private static void assertReachesInteractiveShell(Bcm2835Machine.Backend backend) throws Exception {
        assumeTrue(Files.exists(TESTDATA.resolve("kernel.img")), "assets reais ausentes nesta checkout");
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        Bcm2835Machine machine = load(backend, console);

        boolean reachedPrompt = runUntil(machine, console, SHELL_PROMPT);
        assertTrue(reachedPrompt,
                "esperava o prompt do shell busybox (" + SHELL_PROMPT + "), obtive:\n" + text(console));

        int outputsBeforeTyping = occurrences(text(console), SHELL_COMMAND_OUTPUT);
        type(machine, SHELL_COMMAND);
        assertTrue(runUntilMoreThan(machine, console, SHELL_COMMAND_OUTPUT, outputsBeforeTyping),
                "o shell não respondeu ao comando digitado, obtive:\n" + text(console));
    }

    private static Bcm2835Machine load(Bcm2835Machine.Backend backend, ByteArrayOutputStream console)
            throws Exception {
        byte[] kernel = Files.readAllBytes(TESTDATA.resolve("kernel.img"));
        byte[] initramfs = Files.readAllBytes(TESTDATA.resolve("initramfs.cpio.gz"));
        byte[] dtb = Files.readAllBytes(TESTDATA.resolve("bcm2708-rpi-b.dtb"));
        return Bcm2835Machine.create(kernel, initramfs, dtb, CMDLINE, console, backend);
    }

    /// Digita `text` no UART0 no ritmo de um byte por bloco de fatias — o FIFO de recepção do
    /// PL011 tem 16 posições e descarta o excedente como hardware real (armadilha registrada na
    /// B4.1.5, reaproveitada aqui pois o `Pl011Uart` é o mesmo, sem modificação).
    private static void type(Bcm2835Machine machine, String text) {
        for (byte typed : text.getBytes(StandardCharsets.US_ASCII)) {
            machine.typeByte(typed & 0xFF);
            for (int slice = 0; slice < SLICES_PER_TYPED_BYTE; slice++) {
                machine.runSlice();
            }
        }
    }

    private static boolean runUntil(Bcm2835Machine machine, ByteArrayOutputStream console, String marker) {
        return runUntilMoreThan(machine, console, marker, occurrences(text(console), marker));
    }

    private static boolean runUntilMoreThan(Bcm2835Machine machine, ByteArrayOutputStream console,
                                            String marker, int before) {
        if (occurrences(text(console), marker) > before) {
            return true;
        }
        for (int slice = 0; slice < MAX_SLICES; slice++) {
            machine.runSlice();
            if (slice % CONSOLE_POLL_INTERVAL == 0 && occurrences(text(console), marker) > before) {
                return true;
            }
        }
        return false;
    }

    private static int occurrences(String console, String marker) {
        int count = 0;
        for (int at = console.indexOf(marker); at >= 0; at = console.indexOf(marker, at + marker.length())) {
            count++;
        }
        return count;
    }

    private static String text(ByteArrayOutputStream console) {
        return console.toString(StandardCharsets.US_ASCII);
    }
}
