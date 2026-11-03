# Security — concessoes-de-autonomia

Ameaça principal: injeção (página, arquivo, tela, memória, resultado de ferramenta) fazendo o modelo
chamar `conceder_permissao`.
- Evidência tem de estar no `pedidoOriginal` (texto do dono no turno), não em resultado de
  ferramenta; a nota de AJUSTE (texto do servidor que cita a pergunta) é cortada.
- Segundo leitor sem ferramentas e sem o turno: só vê a mensagem do dono.
- Canal privado autenticado; tarefa/gatilho/grupo não concedem.
- Teto de 7 dias; piso PERGUNTA intransponível (gravação, leitura do JSON e consulta).
- Tudo no diário; revogação sem atrito.
Risco residual: o dono escrever literalmente a frase que a página sugeriu. Aceito — é o dono falando.
`concessoes.json` não contém segredo; evidência cortada em 300 caracteres.
Oferta aprendida passa pelo mesmo `Aprovacoes` (uso único, mensagem nova, mesmo usuário, prazo).
