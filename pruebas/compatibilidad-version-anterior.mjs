// Comprueba que las reglas del proyecto aceptan la forma exacta de los documentos que escribe la V2 de Mis Gastos.
// Usa solo el emulador local. Se corre con: firebase emulators:exec --only firestore --project demo-tp3 "node <este archivo>"
const HOST = process.env.FIRESTORE_EMULATOR_HOST || '127.0.0.1:8080';
const PROYECTO = process.env.GCLOUD_PROJECT || 'demo-tp3';
const RAIZ = `http://${HOST}/v1/projects/${PROYECTO}/databases/(default)/documents`;
const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
const token = (uid) => `${b64({ alg: 'none', typ: 'JWT' })}.${b64({ user_id: uid, sub: uid })}.`;
const UID = 'usuario-v2';
const ahora = () => ({ timestampValue: new Date().toISOString() });
const s = (v) => ({ stringValue: v });
const n = (v) => ({ doubleValue: v });
const i = (v) => ({ integerValue: String(v) });

async function crear(coleccion, campos) {
  const r = await fetch(`${RAIZ}/usuarios/${UID}/${coleccion}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token(UID)}` },
    body: JSON.stringify({ fields: campos }),
  });
  return { status: r.status, id: r.ok ? (await r.json()).name.split('/').pop() : null };
}
async function actualizar(coleccion, id, campos) {
  const r = await fetch(`${RAIZ}/usuarios/${UID}/${coleccion}/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token(UID)}` },
    body: JSON.stringify({ fields: campos }),
  });
  return r.status;
}
async function leer(coleccion) {
  const r = await fetch(`${RAIZ}/usuarios/${UID}/${coleccion}?orderBy=fecha%20desc`, {
    headers: { Authorization: `Bearer ${token(UID)}` },
  });
  return r.status;
}

let fallos = 0;
const ok = (nombre, cond, extra = '') => { console.log((cond ? 'OK   ' : 'FALLA') + ' ' + nombre + (extra ? ' - ' + extra : '')); if (!cond) fallos++; };

// Gasto como lo guarda la V2: monto, categoria (id), detalle, fecha, medioId y fijoId (vacio si lo cargo a mano)
const gasto = { monto: n(2500), categoria: s('AbCdEfGhIjKlMnOpQrSt'), detalle: s('Almuerzo'), fecha: ahora(), medioId: s('efectivo'), fijoId: s('') };
const g = await crear('gastos', gasto);
ok('crear gasto con la forma de la V2', g.status === 200, 'estado ' + g.status);
ok('corregir ese gasto (update completo)', g.id && (await actualizar('gastos', g.id, { ...gasto, monto: n(3000) })) === 200);
ok('crear gasto generado por un fijo (fijoId con valor)', (await crear('gastos', { ...gasto, fijoId: s('xyz123'), detalle: s('Alquiler') })).status === 200);
ok('crear ingreso', (await crear('ingresos', { monto: n(100000), detalle: s('Sueldo'), fecha: ahora() })).status === 200);
const fijo = { nombre: s('Alquiler'), monto: n(180000), categoria: s('AbCdEfGhIjKlMnOpQrSt'), medioId: s('efectivo'), diaDelMes: i(5) };
const f = await crear('fijos', fijo);
ok('crear gasto fijo', f.status === 200, 'estado ' + f.status);
ok('editar gasto fijo', f.id && (await actualizar('fijos', f.id, { ...fijo, monto: n(190000) })) === 200);
ok('crear categoria', (await crear('categorias', { nombre: s('Comida'), color: s('#FF8A65'), icono: s('comida'), orden: i(0) })).status === 200);
ok('crear medio de pago', (await crear('medios', { nombre: s('Efectivo'), color: s('#66BB6A'), icono: s('billetera'), orden: i(0) })).status === 200);
ok('leer la lista completa de gastos ordenada por fecha', (await leer('gastos')) === 200);
ok('leer ingresos', (await leer('ingresos')) === 200);
console.log(fallos === 0 ? '\nLa V2 es compatible con las reglas del proyecto.' : `\n${fallos} fallos.`);
process.exit(fallos === 0 ? 0 : 1);
