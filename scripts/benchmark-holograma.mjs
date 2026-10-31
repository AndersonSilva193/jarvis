#!/usr/bin/env node
// Dispara o benchmark do holograma na API do Jarvis, acompanha e grava o relatório.
//
//   HUD_TOKEN=... node scripts/benchmark-holograma.mjs https://jarvis.exemplo.com [--detalhe normal] [--provedores local,anthropic]
//
// Gera os dez objetos de referência (BenchmarkDeHolograma.OBJETOS) com cada gerador, um de cada vez,
// e grava benchmark-holograma-<data>.md na pasta atual — a mesma cópia fica em HOLOGRAMA_PASTA/benchmark
// na VPS. Com os dois geradores no nível normal leva de 10 a 30 minutos; o Haiku custa centavos.
// Com o benchmark já rodando (409), só acompanha o que está em andamento.

import { writeFileSync } from 'node:fs';

const [api, ...resto] = process.argv.slice(2);
const token = process.env.HUD_TOKEN;
if (!api || !token) {
  console.error('Uso: HUD_TOKEN=... node scripts/benchmark-holograma.mjs <url-da-api> [--detalhe normal] [--provedores local,anthropic]');
  process.exit(2);
}
const opcao = (nome) => {
  const i = resto.indexOf(`--${nome}`);
  return i >= 0 ? resto[i + 1] : undefined;
};
const base = new URL('/api/holograma/benchmark', api);
for (const nome of ['detalhe', 'provedores']) if (opcao(nome)) base.searchParams.set(nome, opcao(nome));
const cabecalhos = { Authorization: `Bearer ${token}` };

const inicio = await fetch(base, { method: 'POST', headers: cabecalhos });
if (inicio.status === 409) console.log('Já há um benchmark rodando; acompanhando esse.');
else if (inicio.status !== 202) {
  console.error(`A API recusou o benchmark: HTTP ${inicio.status}`);
  process.exit(1);
}

let ultimo = '';
for (;;) {
  await new Promise((r) => setTimeout(r, 10_000));
  const resposta = await fetch(new URL('/api/holograma/benchmark', api), { headers: cabecalhos });
  if (!resposta.ok) {
    console.error(`HTTP ${resposta.status} ao consultar; tentando de novo.`);
    continue;
  }
  const andamento = await resposta.json();
  const linha = `${andamento.feitas}/${andamento.total} ${andamento.atual}`;
  if (linha !== ultimo) console.log(linha);
  ultimo = linha;
  if (!andamento.rodando) {
    if (!andamento.markdown) {
      console.error('O benchmark parou sem relatório; veja o log do servidor.');
      process.exit(1);
    }
    const arquivo = `benchmark-holograma-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.md`;
    writeFileSync(arquivo, andamento.markdown);
    console.log(`\n${andamento.markdown}\nRelatório salvo em ${arquivo} (na VPS: ${andamento.relatorio.arquivo}).`);
    break;
  }
}
