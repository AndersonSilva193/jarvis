# Arquivos dos agentes na pasta do PC — plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Livro financeiro e agenda dos agentes do Hermes3D gravados na pasta do Jarvis no PC (com fila quando o PC não responde), leads lidos do `leads.csv`, e as pastas da VPS graváveis.

**Architecture:** O agente do PC ganha três ações de planilha (`acrescentar_linhas`, `alterar_linha`, `remover_linha`) restritas a uma lista de CSVs na raiz de `pastaArquivos`, e anuncia o que sabe no cabeçalho `X-Pc-Acoes`. Na VPS, `EscritaNoPc` manda essas ações e `FilaDoPc` guarda o que não pôde ser gravado e reenvia no `PcVoltou`. `FinancasService` continua validando e mantendo a cópia de leitura (`financas.json`); `AgendaService` ganha escrita.

**Tech Stack:** Java 21, Spring Boot (jarvis), Java puro + Jackson 3 (jarvis-agente-pc), JUnit 5, Maven.

**Spec:** `docs/superpowers/specs/2026-11-11-arquivos-dos-agentes-no-pc-design.md` (neste repositório). Leia antes de começar.

## Global Constraints

- Repositórios: `<projetos>\jarvis-agente-pc` (Tasks 1–2) e `<projetos>\jarvis` (Tasks 3–8).
- Branches: no agente, `feat/planilhas-dos-agentes` a partir de `feat/empresa-hermes3d`; no jarvis, `feat/arquivos-dos-agentes-no-pc` a partir de `origin/feat/servidor-mcp-escritorio` (ponta, hoje `225f2cc`). Não fazer push nem merge sem o dono pedir.
- Maven: não está no PATH. Use `MVN="~/.m2/wrapper/dists/apache-maven-3.9.16/0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0/bin/mvn"` e rode `"$MVN" -q test -Dtest=<Classe>` na raiz de cada repositório.
- O agente do PC **nunca** aceita caminho vindo da VPS: `arquivo` é um nome simples (sem `/`, `\`, `..`, `:`), comparado sem diferenciar maiúsculas com a lista `planilhas.permitidas` (padrão `agenda.csv,financas.csv,leads.csv`).
- CSV escrito: separador `;`, UTF-8 **com BOM**, fim de linha `\r\n`, mesma proteção anti-fórmula do CSV do `CriadorDeArquivo`. Leitura: BOM → UTF-8; senão UTF-8 estrito; se falhar, `windows-1252`.
- Primeira coluna de `agenda.csv` e `financas.csv` é `Id`. Cabeçalhos exatos:
  - `financas.csv`: `Id;Data;Tipo;Valor;Categoria;Descricao;Origem;RegistradoEm;Remove`
  - `agenda.csv`: `Id;Data;Início;Fim;Compromisso;Local`
- Datas escritas `dd/MM/yyyy`; valor em reais `120,00`; `Tipo` ∈ `gasto|ganho|remocao`; `Remove` = `Id` removido (só na linha `remocao`).
- `financas.csv` é só para olhar: a VPS nunca altera nem remove linhas dele, só acrescenta. `agenda.csv` pode ser alterado à mão e pelos agentes.
- Lembretes (`dev.jarvis.lembretes`) não mudam.
- Textos ao dono em português do Brasil, no tom das mensagens existentes. Nenhum caminho pessoal (`C:\Users\<usuário>...`) em arquivo versionado.
- Nada roda na VPS a partir daqui: comandos de deploy vão para o dono (Task 9).

## Review Focus

1. **`agenda.csv` aberto no Excel enquanto o Ledger marca um compromisso** → nada se perde: o agente responde `arquivo_travado`, a VPS enfileira e responde "anotado". (Teste na Task 1 e na Task 5.)
2. **Agenda editada à mão e salva pelo Excel em ANSI, com acento** → lida e regravada sem "Ã§". (Teste na Task 1 e na Task 2.)
3. **O mesmo pendente reenviado duas vezes** (resposta do PC perdida, timeout) → uma linha só no arquivo. (Teste na Task 1 e na Task 5.)
4. **VPS reinicia com pendentes na fila** → a fila volta do disco e esvazia no próximo `PcVoltou`. (Teste na Task 5.)
5. **Agente do PC antigo, online** → itens ficam na fila, o dono recebe **um** aviso para atualizar, sem repetição a cada tentativa. (Teste na Task 5.)

---

### Task 1: Planilhas permitidas no agente do PC

**Files:**
- Create: `src/main/java/dev/jarvis/agente/PlanilhasPermitidas.java`
- Modify: `src/main/java/dev/jarvis/agente/Configuracao.java` (chave `planilhas.permitidas`), `config.properties.example`, `README.md` (seção curta "Planilhas dos agentes")
- Modify (se a regra anti-fórmula estiver privada): `src/main/java/dev/jarvis/agente/CriadorDeArquivo.java` → extrair `static String celulaCsvSegura(String)` package-private
- Test: `src/test/java/dev/jarvis/agente/PlanilhasPermitidasTest.java`

**Interfaces:**
- Produces:
  ```java
  final class PlanilhasPermitidas {
      PlanilhasPermitidas(Path pasta, Set<String> permitidas, Path pastaBackup);
      boolean permitida(String nome);                       // nome simples + na lista
      Saida acrescentar(String nome, List<String> cabecalho, List<List<String>> linhas) throws IOException;
      Saida alterar(String nome, String id, Map<String, String> valores) throws IOException;
      Saida remover(String nome, String id) throws IOException;
      record Saida(int gravadas, int jaExistiam) { }
      static final String NAO_PERMITIDO = "nao_permitido", TRAVADO = "arquivo_travado",
                          ID_INEXISTENTE = "id_inexistente";
  }
  ```
  Falhas são `IOException` cuja mensagem **começa** com uma dessas constantes seguida de `: `.
  `Configuracao.planilhasPermitidas()` → `Set<String>` em minúsculas.

- [ ] **Step 1: Criar o branch** `git switch -c feat/planilhas-dos-agentes` no `jarvis-agente-pc`.
- [ ] **Step 2: Escrever os testes que falham** (`@TempDir pasta`, `@TempDir backup`):
  - `acrescentarCriaOArquivoComCabecalhoEBom`: arquivo inexistente; depois os bytes começam com `EF BB BF` e o texto é `Id;Data;Tipo\r\nl1;11/11/2026;gasto\r\n`.
  - `acrescentarEIdempotentePorId`: acrescentar `l1` duas vezes → uma linha; segunda `Saida` é `(0, 1)`.
  - `acrescentarPreservaLinhasAntigasEPoeNoFim`.
  - `recusaNomeComCaminhoOuForaDaLista`: `"../x.csv"`, `"sub\\agenda.csv"`, `"outro.csv"` → mensagem começa com `nao_permitido`.
  - `arquivoTravadoViraTravado`: segure um `FileChannel` com `lock()` no arquivo (no Windows o `tryLock` de outra abertura falha) → mensagem começa com `arquivo_travado`.
  - `leAnsiDoExcelEGravaUtf8`: arquivo gravado em `windows-1252` com `Reunião`; após `acrescentar`, o arquivo é UTF-8 com BOM e contém `Reunião` correto.
  - `alterarTrocaSoAsColunasPedidas` e `alterarIdInexistente` (prefixo `id_inexistente`).
  - `removerTiraALinhaEGuardaBackup`: após remover, a linha sumiu e existe 1 arquivo em `backup`.
  - `linhaSemIdGanhaIdNaProximaEscrita`: arquivo com linha `;12/11/2026;10:00;;Dentista;` → após qualquer `acrescentar`, essa linha tem `Id` de 8 caracteres hex.
  - `celulaComFormulaGanhaApostrofo`: valor `=1+1` sai `'=1+1`.
- [ ] **Step 3: Rodar** `"$MVN" -q test -Dtest=PlanilhasPermitidasTest` → FAIL (classe não existe).
- [ ] **Step 4: Implementar.** Ler o arquivo inteiro (limite 2 MiB; acima → `IOException`), parser CSV com aspas, `;`. Antes de gravar, abrir `FileChannel` com `WRITE` e `tryLock()`; `FileSystemException`/`OverlappingFileLockException`/lock nulo → `TRAVADO`. Gravação: temporário na mesma pasta + `ATOMIC_MOVE`; antes de `alterar`/`remover`, copiar o original para `pastaBackup/<nome>-<yyyyMMdd-HHmmss>.csv` e manter os 10 mais novos por nome. `Id` novo: 8 hex de `UUID`. Coluna `Id` achada pelo cabeçalho (sem diferenciar maiúsculas); arquivo sem coluna `Id` → `IOException("nao_permitido: sem coluna Id")`.
- [ ] **Step 5: Configuração.** `planilhas.permitidas` em `Configuracao` (padrão `agenda.csv,financas.csv,leads.csv`; nomes inválidos ignorados com aviso no log) e no `config.properties.example` com comentário de uma linha.
- [ ] **Step 6: Rodar** `"$MVN" -q test` → tudo PASS.
- [ ] **Step 7: Commit** `feat: planilhas permitidas para a agenda e o livro dos agentes`.

### Task 2: Ações de planilha, cabeçalho `X-Pc-Acoes`, catálogo e leitura ANSI

**Files:**
- Modify: `src/main/java/dev/jarvis/agente/AgentePc.java` (`ACOES_E2` + despacho), `ClienteJarvis.java` (cabeçalho), `CatalogoDeArquivos.java` (recatalogar permitidas), `LeitorDeArquivo.java` (decodificação do CSV)
- Test: `src/test/java/dev/jarvis/agente/AcoesDePlanilhaTest.java`, e casos novos em `LeituraDeArquivosTest.java` e `ClienteJarvisFocoTest.java` (ou teste novo do cabeçalho no mesmo estilo)

**Interfaces:**
- Consumes: `PlanilhasPermitidas` (Task 1).
- Produces (contrato com a VPS):
  - Ação `acrescentar_linhas`, `dados = {"arquivo": "...", "cabecalho": [..], "linhas": [[..], ..]}` (máx. 500 linhas por comando).
  - Ação `alterar_linha`, `dados = {"arquivo": "...", "id": "...", "valores": {"Coluna": "valor"}}`.
  - Ação `remover_linha`, `dados = {"arquivo": "...", "id": "..."}`.
  - Sucesso: `Resultado.comDados(comando, {"gravadas": n, "jaExistiam": m})`. Falha: `Resultado.falhou(comando, "<constante>: <frase>")`.
  - Cabeçalho `X-Pc-Acoes: acrescentar_linhas,alterar_linha,remover_linha` em toda consulta de comandos (mesmos pontos onde vai `X-Pc-Apps`).
  - `static Resultado AgentePc.executarPlanilha(ComandoRecebido comando, PlanilhasPermitidas planilhas)` (package-private, para teste).

- [ ] **Step 1: Escrever os testes que falham:**
  - `acrescentarLinhasPeloComando` / `alterarLinhaPeloComando` / `removerLinhaPeloComando` via `executarPlanilha` → `ok()` e `dados.gravadas`.
  - `dadosInvalidosFalhamSemTocarNoArquivo` (sem `arquivo`, `linhas` não-matriz, > 500 linhas).
  - `travadoVoltaComPrefixo`: mensagem do `Resultado` começa com `arquivo_travado`.
  - Cabeçalho: a requisição montada pelo `ClienteJarvis` tem `X-Pc-Acoes` com as três ações.
  - `LeituraDeArquivosTest.csvAnsiDoExcelLeComAcento`: `ler_arquivo` de um CSV `windows-1252` com `Reunião` devolve `Reunião`.
  - `LeituraDeArquivosTest.planilhaPermitidaAlteradaPorForaContinuaLegivel`: catalogar `leads.csv`, acrescentar uma linha por fora, `ler_arquivo` funciona e traz a linha nova. E o mesmo para `outro.csv` (fora da lista) continua recusando como "alterado".
- [ ] **Step 2: Rodar** os testes → FAIL.
- [ ] **Step 3: Implementar.** Acrescentar as três ações a `ACOES_E2` e um ramo no despacho que chama `executarPlanilha` e conclui como `concluído`/`falhou`. No `CatalogoDeArquivos`, onde o estado vira `"alterado"`: se o nome está em `planilhas.permitidas`, regravar a entrada (tamanho, data, hash) e tratar como `"disponivel"`. No `LeitorDeArquivo.csv`, trocar a decodificação UTF-8 estrita pela regra das Global Constraints (pode reusar o decodificador da Task 1).
- [ ] **Step 4: Rodar** `"$MVN" -q test` → PASS.
- [ ] **Step 5: Commit** `feat: ações de planilha e anúncio das ações para a VPS`.

### Task 3: VPS — pastas graváveis e agenda em CSV

**Files:**
- Modify: `Dockerfile`, `README.md` (seção de deploy), `src/main/resources/application.yml` (`agenda-arquivo` padrão `agenda.csv`), `.env.example` (`AGENDA_ARQUIVO=agenda.csv`), `docs/ESCRITORIO_MCP.md` (exemplo da agenda com `Id`)
- Test: `src/test/java/dev/jarvis/DockerfileTest.java`

- [ ] **Step 1: Criar o branch** `git switch -c feat/arquivos-dos-agentes-no-pc origin/feat/servidor-mcp-escritorio` no `jarvis` (o spec e este plano, ainda não versionados, vêm junto; inclua-os no commit desta task).
- [ ] **Step 2: Teste que falha** `DockerfileTest.criaTodasAsPastasDosVolumes`: lê `docker-compose.yml`, pega cada destino de volume sob `/data/` e confere que o `Dockerfile` tem esse caminho no `mkdir -p` (hoje faltam `/data/jarvis/diario`, `/data/jarvis/custo`, `/data/jarvis/proativo`, `/data/google`).
- [ ] **Step 3: Rodar** `"$MVN" -q test -Dtest=DockerfileTest` → FAIL listando as quatro pastas.
- [ ] **Step 4: Corrigir o `Dockerfile`** (mesmo `mkdir -p` + `chown -R jarvis:jarvis /data`) e documentar no README o comando único para volumes existentes:
  ```bash
  docker compose stop jarvis
  docker run --rm -v jarvis_diario:/a -v jarvis_custo:/b -v jarvis_proativo:/c -v jarvis_google:/d \
    alpine chown -R 1001:1001 /a /b /c /d
  docker compose up -d --build
  ```
  (com a observação de conferir os nomes reais em `docker volume ls`, prefixo do projeto compose).
- [ ] **Step 5: Trocar o padrão da agenda** para `agenda.csv` no `application.yml`, `.env.example` e docs.
- [ ] **Step 6: Rodar** `"$MVN" -q test` → PASS.
- [ ] **Step 7: Commit** `fix: pastas dos volumes graváveis e agenda em CSV na pasta do PC`.

### Task 4: VPS — ações de planilha no `PcService`

**Files:**
- Modify: `src/main/java/dev/jarvis/pc/ComandoPc.java` (`ACOES` + as três), `src/main/java/dev/jarvis/pc/PcService.java`, `src/main/java/dev/jarvis/web/PcController.java`
- Test: `src/test/java/dev/jarvis/pc/AcoesDoAgenteTest.java`

**Interfaces:**
- Produces:
  - `PcService.proximoComando(Duration espera, String headerApps, String headerIa, String headerFoco, String headerAcoes)` (as sobrecargas antigas repassam `null`).
  - `public boolean PcService.agenteConhece(String acao)`: verdadeiro só se o último `X-Pc-Acoes` recebido contém a ação. Cabeçalho ausente **apaga** (agente antigo), como o `X-Pc-Ia`. Valores fora de `[a-z_]{1,40}` são descartados.
  - `PcController` lê `@RequestHeader(value = "X-Pc-Acoes", required = false)` nos dois endpoints que já leem `X-Pc-Apps`.

- [ ] **Step 1: Testes que falham:** `semCabecalhoNaoConheceNada`, `cabecalhoComAsTresConhece`, `cabecalhoAusenteDepoisApaga`, `lixoNoCabecalhoEIgnorado`, e no teste de `ComandoPc` as três ações estão em `ACOES`.
- [ ] **Step 2: Rodar** → FAIL.
- [ ] **Step 3: Implementar** no padrão de `registrarIa`.
- [ ] **Step 4: Rodar** `"$MVN" -q test` → PASS (atenção a testes que contam `ACOES` ou a lista do schema de `controlar_pc`: as três ações **não** entram no schema da ferramenta do modelo; se `ACOES` alimenta esse schema, crie `ComandoPc.ACOES_DE_PLANILHA` separado e aceite-as só no caminho interno).
- [ ] **Step 5: Commit** `feat: VPS sabe quais ações de planilha o agente do PC conhece`.

### Task 5: VPS — `EscritaNoPc` e `FilaDoPc`

**Files:**
- Create: `src/main/java/dev/jarvis/escritorio/PedidoDeEscrita.java`, `EscritaNoPc.java`, `FilaDoPc.java`
- Test: `src/test/java/dev/jarvis/escritorio/EscritaNoPcTest.java`, `FilaDoPcTest.java`

**Interfaces:**
- Consumes: `PcService.enfileirar(ComandoPc)`, `PcService.online()`, `PcService.agenteConhece(String)` (Task 4); `PcVoltou`; `ArquivoAtomico`; `TelegramClient.enviarMensagem(long, String)` com `props.telegram().usuarioPermitido()`.
- Produces:
  ```java
  public record PedidoDeEscrita(String id, String acao, String arquivo, ObjectNode dados, Instant criadoEm) { }
  @Component public class EscritaNoPc {
      public enum Estado { GRAVADO, PC_OFFLINE, ARQUIVO_TRAVADO, AGENTE_ANTIGO, ID_INEXISTENTE, FALHOU }
      public record Resposta(Estado estado, String mensagem) { }
      public Resposta enviar(PedidoDeEscrita pedido);          // espera até 30 s
      interface Envio { CompletableFuture<ResultadoPc> enviar(ComandoPc c); }   // teste troca
  }
  @Component public class FilaDoPc {
      public synchronized EscritaNoPc.Resposta gravarOuEnfileirar(PedidoDeEscrita pedido);
      public synchronized int pendentes();
      @EventListener public void pcVoltou(PcVoltou evento);    // chama esvaziar()
      @Scheduled(fixedDelay = 300_000, initialDelay = 60_000) public void esvaziar();
  }
  ```
  Fila persistida em `DIARIO_PASTA/pendentes-pc.json` (lista de `PedidoDeEscrita`). O `id` do comando no PC é sempre `pedido.id()`.

- [ ] **Step 1: Testes que falham — `EscritaNoPcTest`:** offline → `PC_OFFLINE` sem enviar; online e `agenteConhece` falso → `AGENTE_ANTIGO` sem enviar; resultado ok → `GRAVADO`; mensagem `arquivo_travado: ...` → `ARQUIVO_TRAVADO`; `id_inexistente: ...` → `ID_INEXISTENTE`; timeout → `FALHOU`.
- [ ] **Step 2: Testes que falham — `FilaDoPcTest`** (fake `EscritaNoPc`, `@TempDir`):
  - `gravaDiretoQuandoFilaVaziaEPcResponde` → `GRAVADO`, `pendentes()==0`.
  - `travadoOfflineOuFalhaVaoParaAFila` → estado devolvido, `pendentes()==1`.
  - `novoPedidoComFilaCheiaEntraNoFimEPreservaOrdem`: com 1 pendente e PC respondendo, o novo pedido é gravado **depois** do antigo (ordem de envio no fake).
  - `filaSobreviveAoReinicio`: nova instância sobre a mesma pasta → `pendentes()==1`; `pcVoltou` esvazia.
  - `reenvioDoMesmoIdNaoDuplica`: o fake simula "já existia" (`jaExistiam=1`) → tratado como `GRAVADO`, sai da fila.
  - `agenteAntigoAvisaUmaVez`: três `esvaziar()` com `AGENTE_ANTIGO` → uma mensagem no Telegram fake contendo "atualize o agente do PC" e o número de pendentes; volta a avisar só depois de um `GRAVADO` ou reinício.
  - `idInexistenteSaiDaFilaEAvisa`: `remover_linha`/`alterar_linha` com `ID_INEXISTENTE` sai da fila e manda um aviso; para `remover_linha` reenviado após `FALHOU`, `ID_INEXISTENTE` conta como feito, sem aviso.
  - `paraNoPrimeiroErro`: com 3 pendentes e o 1º travado, nenhum dos outros é enviado.
- [ ] **Step 3: Rodar** os dois testes → FAIL.
- [ ] **Step 4: Implementar** (falha de gravação do `pendentes-pc.json` → `ERROR` no log e o pedido continua em memória; o chamador recebe o estado normalmente).
- [ ] **Step 5: Rodar** `"$MVN" -q test` → PASS.
- [ ] **Step 6: Commit** `feat: escrita na pasta do PC com fila para quando ele não responde`.

### Task 6: Livro financeiro no `financas.csv`

**Files:**
- Create: `src/main/java/dev/jarvis/financas/LivroNoPc.java` (monta linhas, migração, importação)
- Modify: `src/main/java/dev/jarvis/financas/FinancasService.java`, `ferramentas/RegistrarLancamentoFerramenta.java`, `ferramentas/RemoverLancamentoFerramenta.java`, `mcp/RegistrarLancamentoMcp.java`
- Test: `src/test/java/dev/jarvis/financas/LivroNoPcTest.java`, casos novos em `FinancasServiceTest.java`, `ServidorMcpTest.java`

**Interfaces:**
- Consumes: `FilaDoPc.gravarOuEnfileirar`, `PedidoDeEscrita`, `EscritaNoPc.Estado` (Task 5); `PlanilhaDoPc.ler(nome, aba, maxLinhas)`.
- Produces:
  ```java
  public record Registrado(Lancamento lancamento, EscritaNoPc.Estado noPc) {
      public boolean gravadoNoPc() { return noPc == EscritaNoPc.Estado.GRAVADO; }
  }
  // FinancasService
  public synchronized Registrado registrar(Lancamento.Tipo, double, String, String, String, String) throws IOException;
  public synchronized Optional<Registrado> remover(String id, String origem) throws IOException;
  public static String confirmacao(Registrado r);
  // LivroNoPc
  static final String ARQUIVO = "financas.csv";
  static final List<String> CABECALHO = List.of("Id","Data","Tipo","Valor","Categoria","Descricao","Origem","RegistradoEm","Remove");
  static List<String> linha(Lancamento l, ZoneId zona);              // gasto/ganho
  static List<String> linhaDeRemocao(String idNovo, Lancamento removido, Instant quando, ZoneId zona);
  static List<Lancamento> importar(PlanilhaDoPc.Tabela tabela);       // inverso, ignora linha inválida
  ```
  O construtor de teste de `FinancasService` ganha `FilaDoPc fila` (nulo = sem PC, estado `GRAVADO` fingido para não mudar os testes antigos).

- [ ] **Step 1: Testes que falham:**
  - `LivroNoPcTest.linhaDeGasto`: gasto de 12000 centavos, `Claude Pro`, categoria `ia`, data 11/11/2026 → `["<id>","11/11/2026","gasto","120,00","ia","Claude Pro","escritorio","11/11/2026 HH:mm",""]`.
  - `LivroNoPcTest.linhaDeRemocao` → `Tipo` `remocao`, `Remove` = id removido.
  - `LivroNoPcTest.importarIgnoraLinhaInvalidaERespeitaRemocao`.
  - `FinancasServiceTest.registrarComPcGravaNoLivroDoPc`: fake fila recebe `acrescentar_linhas` em `financas.csv` com `CABECALHO` e a linha; `Registrado.gravadoNoPc()`.
  - `FinancasServiceTest.registrarComPcForaFicaNaFilaEConfirmaComAviso`: `confirmacao` contém "quando o PC voltar"; o lançamento já entra no `resumo`.
  - `FinancasServiceTest.travadoAvisaQueOArquivoEstaAberto`: `confirmacao` contém "aberto no PC".
  - `FinancasServiceTest.removerAcrescentaLinhaDeRemocaoNuncaAlteraLinha`: a fila só recebe `acrescentar_linhas`.
  - `FinancasServiceTest.migracaoEnfileiraLancamentosAntigosUmaVez`: `financas.json` com 2 lançamentos (1 removido) e sem o marcador `financas-pc-migrado` → na partida, um pedido com 3 linhas (2 + remoção) e o marcador criado; segunda partida não enfileira nada.
  - `FinancasServiceTest.copiaVaziaImportaDoPcNoPcVoltou`: cópia vazia + fake `PlanilhaDoPc` com 2 linhas → `resumo` soma as 2; cópia não vazia → não lê o PC.
  - `ServidorMcpTest`: `registrar_gasto` continua devolvendo os campos de hoje e ganha `"noPc": true|false`; `resumo_financeiro` inalterado.
- [ ] **Step 2: Rodar** → FAIL.
- [ ] **Step 3: Implementar.** Validação e gravação na cópia como hoje; depois `fila.gravarOuEnfileirar(new PedidoDeEscrita(UUID, "acrescentar_linhas", "financas.csv", {cabecalho, linhas:[linha]}, agora))`. Textos de `confirmacao`: GRAVADO → o de hoje; PC_OFFLINE/FALHOU/AGENTE_ANTIGO → o de hoje + " Anotado; lanço no livro do PC quando ele voltar."; ARQUIVO_TRAVADO → + " O livro está aberto no PC; lanço assim que ele for fechado." Migração em `@EventListener(ApplicationReadyEvent.class)`, marcador `DIARIO_PASTA/financas-pc-migrado`, lotes de 500 linhas. Importação no `@EventListener PcVoltou` só com a cópia vazia, `PlanilhaDoPc.ler("financas.csv", "", 800)`; se o arquivo tiver mais de 800 linhas, log `WARN` e importa as 800 últimas.
- [ ] **Step 4: Rodar** `"$MVN" -q test` → PASS.
- [ ] **Step 5: Commit** `feat: livro financeiro gravado no financas.csv da pasta do PC`.

### Task 7: Agenda com escrita (`registrar`, `alterar`, `cancelar`)

**Files:**
- Modify: `src/main/java/dev/jarvis/escritorio/AgendaService.java`, `ferramentas/AgendaDeHojeFerramenta.java`, `mcp/AgendaDeHojeMcp.java`
- Create: `ferramentas/RegistrarCompromissoFerramenta.java`, `ferramentas/AlterarCompromissoFerramenta.java`, `ferramentas/CancelarCompromissoFerramenta.java`, `mcp/RegistrarCompromissoMcp.java`, `mcp/AlterarCompromissoMcp.java`, `mcp/CancelarCompromissoMcp.java`
- Test: casos novos em `AgendaServiceTest.java`, `ServidorMcpTest.java`; ajustar a contagem no teste de registro de ferramentas (`ContextoDaAplicacaoTest` / o teste que listou "finance and agenda tools")

**Interfaces:**
- Consumes: `FilaDoPc`, `PedidoDeEscrita`, `EscritaNoPc.Estado` (Task 5).
- Produces:
  ```java
  // AgendaService
  public static final String CABECALHO_CSV = "Id;Data;Início;Fim;Compromisso;Local"; // como List<String> também
  public record Compromisso(String id, String inicio, String fim, String titulo) { }   // ganha id
  public record Gravacao(String id, EscritaNoPc.Estado estado) { }
  public Gravacao registrar(String data, String inicio, String fim, String titulo, String local);
  public Gravacao alterar(String id, Map<String, String> campos);  // chaves: data, inicio, fim, compromisso, local
  public Gravacao cancelar(String id);
  ```
  Ferramentas da conversa no mesmo grupo de `AgendaDeHojeFerramenta`, `mudaEstado() = true`. MCP: `registrar_compromisso`, `alterar_compromisso`, `cancelar_compromisso`, com `structuredContent {"id", "noPc"}`.

- [ ] **Step 1: Testes que falham:**
  - `registrarMandaLinhaComIdNoCabecalhoDaAgenda`: `("12/11/2026","14:00","","Reunião com cliente","Batel")` → pedido `acrescentar_linhas` em `agenda.csv`, `CABECALHO_CSV`, linha `[id8,"12/11/2026","14:00","","Reunião com cliente","Batel"]`.
  - `aceitaDataIsoEHoraSemZero`: `"2026-11-12"`, `"9:00"` → `"12/11/2026"`, `"09:00"`.
  - `recusaEntradaInvalida`: data inválida, hora `25:00`, título vazio ou > 80 → `IllegalArgumentException` com frase, e nada na fila.
  - `alterarMandaSoOsCamposPedidos` → `alterar_linha` com `valores` usando os **nomes do cabeçalho** (`Início`, `Compromisso`...).
  - `cancelarMandaRemoverLinha`.
  - `agendaDeHojeMostraOId`: o texto do dia tem o id de cada compromisso, para o agente poder alterar.
  - MCP: os três nomes aparecem em `tools/list`; argumento errado volta `isError`.
- [ ] **Step 2: Rodar** → FAIL.
- [ ] **Step 3: Implementar** reusando os limites (`MAX_TITULO`) e a limpeza de texto que o `AgendaService` já aplica na leitura. Mensagens: GRAVADO → "Marquei: <título>, <data> <hora> (id <id>)."; demais estados com o mesmo aviso do livro ("quando o PC voltar" / "aberto no PC").
- [ ] **Step 4: Rodar** `"$MVN" -q test` → PASS.
- [ ] **Step 5: Atualizar docs** (`docs/ESCRITORIO_MCP.md`: as três ferramentas e o `agenda.csv`; `CLAUDE.md` se ele lista ferramentas) e **commit** `feat: agenda na pasta do PC com escrita pelos agentes`.

### Task 8: Revisão do branch do jarvis

- [ ] **Step 1:** `"$MVN" -q verify` no `jarvis` e `"$MVN" -q package` no `jarvis-agente-pc` → sucesso; gera `target\jarvis-agente-pc.jar`.
- [ ] **Step 2:** Conferir a Review Focus: cada item tem teste passando. Conferir que nenhum arquivo versionado tem caminho pessoal (`git grep` pelo nome do usuário vazio nos dois repositórios).

### Task 9: Instruções dos agentes e entrega (com o dono)

Fora dos repositórios. Cada passo marcado **(dono)** só acontece com a confirmação dele.

- [ ] **Step 1: Instruções dos agentes.** Com o Hermes3D parado, copiar `~/.hermes/hermes3d-agents.json` para `hermes3d-agents.json.bak-<data>` e, em `files`:
  - `closer-ebe396/AGENTS.md`: trocar `<a pastaArquivos do agente do PC>` por `%USERPROFILE%\OneDrive\Documentos\Jarvis\leads.csv`.
  - `ledger-cfec35/AGENTS.md`: substituir a linha "• Financeiro: ..." por um bloco curto: agenda e livro ficam na pasta do Jarvis no PC (`%USERPROFILE%\OneDrive\Documentos\Jarvis`); use `agenda_de_hoje`, `registrar_compromisso`, `alterar_compromisso` (pelo id), `cancelar_compromisso`; `registrar_gasto`, `registrar_ganho`, `remover_lancamento`, `resumo_financeiro` só com valores que o dono informou ou confirmou; nunca editar `financas.csv` direto nem procurar arquivos na VPS; "anotado, lanço quando o PC voltar" é sucesso, não erro.
  - Conferir com `node -e` que o JSON continua válido.
- [ ] **Step 2 (dono): Agente do PC novo.** Parar a tarefa `JarvisAgentePc`, trocar o jar, iniciar de novo.
- [ ] **Step 3 (dono): Merge e deploy da VPS.** Merge dos branches; na VPS, o comando de `chown` da Task 3 e `docker compose up -d --build`.
- [ ] **Step 4: Verificação ponta a ponta.** Pelo Ledger: `registrar_gasto` de R$ 120,00, categoria `ia`, "Claude Pro" → resposta sem erro; `financas.csv` aparece em `Documentos\Jarvis` com a linha; `resumo_financeiro` do mês inclui os R$ 120,00; o card de dinheiro do Hermes3D mostra o valor. Marcar um compromisso pelo Ledger e ver a linha no `agenda.csv`.
