# AGENTS.md

As instruções deste repositório para agentes de código vivem num único arquivo:
**[CLAUDE.md](CLAUDE.md)**.

Leia o `CLAUDE.md` antes de mexer em qualquer coisa aqui. Ele cobre a stack, o mapa do código,
as convenções de nome e de HTTP/JSON, como criar ferramenta nova, onde entra configuração, as
regras de segurança, como validar (`mvn -q -B package`) e como subir.

Este arquivo é só um ponteiro, de propósito: manter duas cópias do mesmo conteúdo foi o que fez
uma delas envelhecer e passar a chamar o modelo pelo nome errado. O modelo usado pelo Jarvis é o
**Claude**, da Anthropic, chamado pela Messages API.
