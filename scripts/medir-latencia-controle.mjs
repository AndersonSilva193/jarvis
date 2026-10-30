// Mede a ida e volta do controle do holograma pela VPS, como o celular mede: celular → VPS → HUD →
// VPS → celular. Abre os dois papéis do /ws/holograma daqui (a HUD com bilhete, o "celular" com o
// código do QR) e manda 200 ecos. Node 24 (WebSocket e fetch nativos), sem dependência.
//
//   HUD_TOKEN=... node scripts/medir-latencia-controle.mjs https://jarvis.barberagendaonline.store
//
// O token vai por variável de ambiente para não ficar no histórico do shell. Rodando do PC da sala
// na mesma rede do celular, o número é o que o dono vê na tela do /controle.

const api = (process.argv[2] ?? '').replace(/\/+$/, '');
const token = process.env.HUD_TOKEN ?? '';
const AMOSTRAS = Number(process.env.AMOSTRAS ?? 200);
if (!api || !token) {
  console.error('uso: HUD_TOKEN=... node scripts/medir-latencia-controle.mjs https://host');
  process.exit(2);
}
const ws = (query) => `${api.replace(/^http/, 'ws')}/ws/holograma?${new URLSearchParams(query)}`;

function abrir(url) {
  return new Promise((resolver, rejeitar) => {
    const s = new WebSocket(url);
    const fila = [];
    const esperando = [];
    s.onmessage = (e) => (esperando.length ? esperando.shift()(String(e.data)) : fila.push(String(e.data)));
    s.onopen = () => resolver({
      s,
      proxima: (ms = 5000) => new Promise((ok, erro) => {
        if (fila.length) return ok(fila.shift());
        const t = setTimeout(() => erro(new Error('nada chegou')), ms);
        esperando.push((m) => { clearTimeout(t); ok(m); });
      }),
    });
    s.onclose = (e) => rejeitar(new Error(`fechou: ${e.code} ${e.reason}`));
    s.onerror = () => undefined;
  });
}

const resposta = await fetch(`${api}/api/holograma/controle/bilhete`, { method: 'POST', headers: { Authorization: `Bearer ${token}` } });
if (!resposta.ok) throw new Error(`bilhete: HTTP ${resposta.status}`);
const { bilhete } = await resposta.json();

const hud = await abrir(ws({ bilhete }));
hud.s.send(JSON.stringify({ t: 'parear' }));
const { codigo } = JSON.parse(await hud.proxima());
const celular = await abrir(ws({ par: codigo }));
await celular.proxima(); // pareado
await hud.proxima(); // controle conectado

const ms = [];
for (let n = 0; n < AMOSTRAS; n++) {
  const inicio = performance.now();
  celular.s.send(JSON.stringify({ t: 'eco', n }));
  hud.s.send(await hud.proxima());
  await celular.proxima();
  ms.push(performance.now() - inicio);
  await new Promise((r) => setTimeout(r, 30)); // abaixo dos 40/s do servidor
}
ms.sort((a, b) => a - b);
const p = (q) => ms[Math.min(ms.length - 1, Math.ceil(ms.length * q) - 1)].toFixed(1);
console.log(`ida e volta pela VPS: p50=${p(0.5)} ms p95=${p(0.95)} ms max=${ms.at(-1).toFixed(1)} ms (${ms.length} amostras)`);
console.log(Number(p(0.95)) > 150 ? 'acima de 150 ms: ver o WebRTC DataChannel no HOLOGRAMA.md' : 'abaixo de 150 ms');

celular.s.onclose = hud.s.onclose = null;
celular.s.close(1000);
hud.s.close(1000);
