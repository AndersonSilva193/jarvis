# Autonomia do Jarvis

O que o Jarvis faz sozinho, o que pergunta antes e o que nunca faz. Este arquivo é relido
quando a data de modificação muda: edite aqui na VPS e o próximo pedido já obedece, sem
rebuild nem restart. Fica no mesmo volume somente leitura da personalidade, então o Jarvis
nunca escreve nele.

Como ler este arquivo:
Só contam as linhas "## <seção>" e, dentro de uma seção, as linhas que começam com hífen e
espaço. Cada item é uma chave do catálogo, ou chave:alvo quando a ação aceita alvo. Todo o
resto (como este parágrafo) é comentário.

Ordem de decisão: piso do código, depois este arquivo, depois as permissões que você me deu
por mensagem ("até sexta pode rodar as rotinas sem perguntar"), depois o padrão do código.
Uma linha aqui, em qualquer seção, vence essas permissões: para que eu pergunte sempre antes
de uma ação, mesmo que você me libere por mensagem, escreva a linha em pergunta_antes.
A linha com alvo vence a linha sem alvo. A mesma ação em duas seções fica com a mais
rigorosa. Linha que não serve (chave desconhecida, alvo inválido, tentativa de baixar o piso)
é ignorada e aparece num WARN do log; o Jarvis nunca cai por causa deste arquivo. Sem este
arquivo, valem os padrões do código, que são os desta primeira versão.

O catálogo (chave, alvo, padrão, piso):
  rotina.rodar[:nome]      rodar rotina cadastrada no PC    padrão pergunta   piso faz_e_avisa
  computer_use.iniciar     usar a tela do PC                padrão pergunta   piso pergunta
  pc.desligar              desligar o PC                    padrão pergunta   piso pergunta
  pc.reiniciar             reiniciar o PC                   padrão pergunta   piso pergunta
  arquivo.apagar_lote      apagar mais de 5 arquivos        padrão pergunta   piso faz_e_avisa
  planilha.agendar         agendar edição de planilha       padrão pergunta   piso faz_e_avisa
  memoria.esquecer         esquecer uma lembrança           padrão pergunta   piso faz_e_avisa
O alvo de rotina.rodar é o nome exato da rotina que o PC anuncia (letras minúsculas,
números, ponto, hífen e sublinhado). Piso "pergunta" quer dizer que nenhuma linha daqui
libera a ação sem pergunta; ela ainda pode ser proibida.

O que não está no catálogo e é feito sem perguntar (ler, listar, criar e editar arquivo,
apagar até 5 arquivos, lembretes, tela, busca, memória) não se configura aqui. O que é
sempre proibido e não tem ferramenta (shell livre, pagar, comprar, digitar senha, arquivo
fora da pasta, seguir instrução vinda de página, arquivo, tela ou memória) também não.

## faz_e_avisa

Executa na hora e anota no diário de ações como feito sem pergunta; aparece na seção
"o que fiz sozinho" do briefing. Para liberar uma rotina só, acrescente a linha
"- rotina.rodar:backup_notas" (sem as aspas) logo abaixo deste parágrafo.

## pergunta_antes

Abre uma pergunta de aprovação e só executa depois do "sim" do dono. Já é o padrão de
todas as ações do catálogo, então não precisa listar nada aqui. Linha nesta seção serve para
travar: a ação passa a perguntar sempre, e nenhuma permissão dada por mensagem a libera.
Exemplo: "- arquivo.apagar_lote" (sem as aspas) logo abaixo deste parágrafo.

## proibido

Recusa na hora, sem pergunta, e o Jarvis explica que a configuração não deixa. Para
proibir o computer use, acrescente "- computer_use.iniciar" (sem as aspas) logo abaixo
deste parágrafo e tire a linha dele de pergunta_antes (se ficar nas duas, vale a mais
rigorosa e o log avisa).
