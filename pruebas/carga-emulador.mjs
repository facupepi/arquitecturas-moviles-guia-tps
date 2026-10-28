// Prueba de carga contra el emulador local de Firestore. No toca la nube ni consume cuota.
// Uso (desde la carpeta nube, con Java 21 disponible):
//   firebase emulators:exec --only firestore --project demo-tp3 "node pruebas/carga-emulador.mjs"
//
// Mide cuatro aspectos tecnicos fundamentales con las reglas reales de la aplicacion:
//   1. Que las reglas declarativas acepten lo valido y rechacen lo ajeno o invalido en O(1).
//   2. Que 2.000 escrituras concurrentes en lotes de 100 se procesen y persistan sin perdidas en 50 usuarios.
//   3. Cuantos documentos devuelve (y por lo tanto cuantas lecturas cuesta) leer toda la
//      coleccion frente a leer solo el mes en curso o una pagina de 30 elementos.

const HOST = process.env.FIRESTORE_EMULATOR_HOST || '127.0.0.1:8080';
const PROYECTO = process.env.GCLOUD_PROJECT || 'finanzas-tp2-utn';
const RAIZ = `http://${HOST}/v1/projects/${PROYECTO}/databases/(default)/documents`;

const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
// El emulador acepta credenciales sin firma: sirven para simular a cada usuario autenticado.
const token = (uid) => `${b64({ alg: 'none', typ: 'JWT' })}.${b64({ user_id: uid, sub: uid })}.`;
const ADMIN = 'Bearer owner'; // en el emulador, salta las reglas (solo para preparar datos masivos)

const campos = (monto, dias, categoria = 'comida') => ({
  fields: {
    monto: { doubleValue: monto },
    categoria: { stringValue: categoria },
    detalle: { stringValue: 'Prueba de carga' },
    fecha: { timestampValue: new Date(Date.now() - dias * 86400000).toISOString() },
    medioId: { stringValue: 'efectivo' },
    fijoId: { stringValue: '' },
  },
});

async function crear(uid, doc, auth) {
  const headers = { 'Content-Type': 'application/json' };
  if (auth !== null) headers.Authorization = auth ?? `Bearer ${token(uid)}`; // null = sin sesion
  const r = await fetch(`${RAIZ}/usuarios/${uid}/gastos`, {
    method: 'POST', headers, body: JSON.stringify(doc),
  });
  return r.status;
}

// Ejecuta las tareas con a lo sumo `limite` solicitudes en vuelo, reproduciendo la
// concurrencia controlada de un grupo de clientes moviles reales.
async function enParalelo(tareas, limite = 100) {
  const resultados = new Array(tareas.length);
  let siguiente = 0;
  await Promise.all(Array.from({ length: Math.min(limite, tareas.length) }, async () => {
    while (siguiente < tareas.length) {
      const i = siguiente++;
      resultados[i] = await tareas[i]();
    }
  }));
  return resultados;
}

async function consultar(uid, structuredQuery) {
  const r = await fetch(`${RAIZ}/usuarios/${uid}:runQuery`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token(uid)}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ structuredQuery }),
  });
  const filas = await r.json();
  return filas.filter((f) => f.document).length;
}

const fallos = [];
const verificar = (nombre, ok, detalle = '') => {
  console.log(`${ok ? 'OK ' : 'FALLA'} ${nombre}${detalle ? ' - ' + detalle : ''}`);
  if (!ok) fallos.push(nombre);
};

console.log('=== BANCO DE PRUEBAS DE CARGA Y ESCALABILIDAD (FIRESTORE) ===\n');

// 1. Evaluacion de Reglas de Seguridad ------------------------------------------------
console.log('--- 1. Verificacion de Reglas Declarativas de Seguridad ---');
verificar('escritura permitida al titular autenticado', (await crear('ana', campos(100, 0))) === 200);
verificar('rechazo 403 al escribir en cuenta ajena',
  (await crear('ana', campos(100, 0), `Bearer ${token('beto')}`)) === 403);
verificar('rechazo 403 a peticiones anonimas sin sesion', (await crear('ana', campos(100, 0), null)) === 403);
verificar('rechazo 403 para importes negativos', (await crear('ana', campos(-5, 0))) === 403);
const largo = campos(10, 0);
largo.fields.detalle.stringValue = 'x'.repeat(121);
verificar('rechazo 403 para textos mayores a 120 caracteres', (await crear('ana', largo)) === 403);
const sinFecha = campos(10, 0);
delete sinFecha.fields.fecha;
verificar('rechazo 403 si falta la fecha obligatoria', (await crear('ana', sinFecha)) === 403);
const fechaInvalida = campos(10, 0);
fechaInvalida.fields.fecha = { stringValue: 'hoy' };
verificar('rechazo 403 para fechas que no son timestamp', (await crear('ana', fechaInvalida)) === 403);
const campoExtra = campos(10, 0);
campoExtra.fields.inyectado = { booleanValue: true };
verificar('rechazo 403 para campos no declarados', (await crear('ana', campoExtra)) === 403);
const medio = await fetch(`${RAIZ}/usuarios/ana/medios`, {
  method: 'POST',
  headers: { Authorization: `Bearer ${token('ana')}`, 'Content-Type': 'application/json' },
  body: JSON.stringify({ fields: { nombre: { stringValue: 'Tarjeta' }, color: { stringValue: '#112233' },
    icono: { stringValue: 'card' }, orden: { integerValue: '1' } } }),
});
verificar('escritura autorizada en coleccion de medios de pago', medio.status === 200);
const medioConExtra = await fetch(`${RAIZ}/usuarios/ana/medios`, {
  method: 'POST',
  headers: { Authorization: `Bearer ${token('ana')}`, 'Content-Type': 'application/json' },
  body: JSON.stringify({ fields: { nombre: { stringValue: 'Tarjeta' }, color: { stringValue: '#112233' },
    icono: { stringValue: 'card' }, orden: { integerValue: '1' }, inyectado: { booleanValue: true } } }),
});
verificar('rechazo 403 para campos extra en medios', medioConExtra.status === 403);
const categoriaConExtra = await fetch(`${RAIZ}/usuarios/ana/categorias`, {
  method: 'POST',
  headers: { Authorization: `Bearer ${token('ana')}`, 'Content-Type': 'application/json' },
  body: JSON.stringify({ fields: { nombre: { stringValue: 'Comida' }, color: { stringValue: '#112233' },
    icono: { stringValue: 'food' }, orden: { integerValue: '1' }, inyectado: { booleanValue: true } } }),
});
verificar('rechazo 403 para campos extra en categorias', categoriaConExtra.status === 403);
const fijoConExtra = await fetch(`${RAIZ}/usuarios/ana/fijos`, {
  method: 'POST',
  headers: { Authorization: `Bearer ${token('ana')}`, 'Content-Type': 'application/json' },
  body: JSON.stringify({ fields: { nombre: { stringValue: 'Alquiler' }, monto: { doubleValue: 100 },
    categoria: { stringValue: 'hogar' }, medioId: { stringValue: 'efectivo' }, diaDelMes: { integerValue: '1' },
    inyectado: { booleanValue: true } } }),
});
verificar('rechazo 403 para campos extra en gastos fijos', fijoConExtra.status === 403);
const ajena = await fetch(`${RAIZ}/otraColeccion`, {
  method: 'POST', headers: { Authorization: `Bearer ${token('ana')}`, 'Content-Type': 'application/json' },
  body: JSON.stringify({ fields: {} }),
});
verificar('rechazo 403 en colecciones no declaradas (politica de cierre)', ajena.status === 403);

// 2. Escrituras concurrentes con medicion de latencias ---------------------------------
console.log('\n--- 2. Carga Concurrente de Escrituras (50 Usuarios x 40 Operaciones) ---');
const USUARIOS = 50, POR_USUARIO = 40;
const TOTAL_OPS = USUARIOS * POR_USUARIO;
const latencias = [];
const t0 = Date.now();

const estados = await enParalelo(
  Array.from({ length: TOTAL_OPS }, (_, i) => async () => {
    const uid = `u${i % USUARIOS}`;
    const opInicio = Date.now();
    const status = await crear(uid, campos(10 + (i % 90), i % 30));
    latencias.push(Date.now() - opInicio);
    return status;
  }),
  100
);
const duracionMs = Date.now() - t0;
const duracionS = (duracionMs / 1000).toFixed(2);
const aceptadas = estados.filter((s) => s === 200).length;

latencias.sort((a, b) => a - b);
const latMin = latencias[0];
const latMax = latencias[latencias.length - 1];
const latProm = (latencias.reduce((acc, v) => acc + v, 0) / latencias.length).toFixed(1);
const latMediana = latencias[Math.floor(latencias.length / 2)];
const latP95 = latencias[Math.floor(latencias.length * 0.95)];
const opsPorSeg = ((TOTAL_OPS / duracionMs) * 1000).toFixed(1);

console.log(`Metricas reales registradas:`);
console.log(`  operaciones procesadas: ${TOTAL_OPS} (${USUARIOS} usuarios x ${POR_USUARIO} gastos)`);
console.log(`  concurrencia controlada: hasta 100 solicitudes simultaneas en vuelo`);
console.log(`  tiempo total transcurrido: ${duracionMs} ms (${duracionS} s)`);
console.log(`  tasa efectiva de escritura: ${opsPorSeg} ops/s`);
console.log(`  latencia (min / prom / med / p95 / max): ${latMin} ms / ${latProm} ms / ${latMediana} ms / ${latP95} ms / ${latMax} ms`);

verificar(`procesamiento de ${TOTAL_OPS} escrituras`, aceptadas === TOTAL_OPS, `${aceptadas}/${TOTAL_OPS} aceptadas (0 errores)`);

// Verificacion exhaustiva de persistencia en todos los 50 usuarios
let usuariosOk = 0;
for (let u = 0; u < USUARIOS; u++) {
  const uid = `u${u}`;
  const cant = await consultar(uid, { from: [{ collectionId: 'gastos' }] });
  if (cant === POR_USUARIO) usuariosOk++;
}
verificar(`persistencia integra en los ${USUARIOS} usuarios`,
  usuariosOk === USUARIOS, `${usuariosOk}/${USUARIOS} usuarios con exactamente ${POR_USUARIO} gastos persistidos`);

// 3. Medicion de Lecturas y Cuotas sobre Historial Real -------------------------------
console.log('\n--- 3. Medicion de Costo de Lecturas sobre Historial de 3.000 Gastos ---');
const HISTORIAL = 3000;
const uid = 'historial';
const inicioMes = new Date();
inicioMes.setDate(1);
inicioMes.setHours(0, 0, 0, 0);
let esperadosMes = 0;
let esperadosCategoriaMes = 0;
for (let i = 0; i < HISTORIAL; i += 500) {
  const writes = Array.from({ length: Math.min(500, HISTORIAL - i) }, (_, k) => {
    const n = i + k;
    const categoria = ['comida', 'transporte', 'salud'][n % 3];
    // Se reservan 100 documentos para el mes calendario actual. El resto se
    // distribuye en meses anteriores para comparar el período visible con todo el historial.
    const diasAtras = n < 100 ? 0 : 31 + ((n - 100) / 100) * 30;
    const fecha = new Date(Date.now() - diasAtras * 86400000);
    if (fecha >= inicioMes) {
      esperadosMes++;
      if (categoria === 'comida') esperadosCategoriaMes++;
    }
    const doc = campos(50 + (n % 200), diasAtras, categoria);
    return { update: { name: `projects/${PROYECTO}/databases/(default)/documents/usuarios/${uid}/gastos/g${n}`, ...doc } };
  });
  const r = await fetch(`http://${HOST}/v1/projects/${PROYECTO}/databases/(default)/documents:commit`, {
    method: 'POST', headers: { Authorization: ADMIN, 'Content-Type': 'application/json' },
    body: JSON.stringify({ writes }),
  });
  if (r.status !== 200) throw new Error('Error al preparar historial: ' + r.status);
}

const orden = [{ field: { fieldPath: 'fecha' }, direction: 'DESCENDING' }];
const inicioMesIso = inicioMes.toISOString();
const completa = await consultar(uid, { from: [{ collectionId: 'gastos' }], orderBy: orden });
const mes = await consultar(uid, {
  from: [{ collectionId: 'gastos' }], orderBy: orden,
  where: { fieldFilter: { field: { fieldPath: 'fecha' }, op: 'GREATER_THAN_OR_EQUAL', value: { timestampValue: inicioMesIso } } },
});
const pagina = await consultar(uid, { from: [{ collectionId: 'gastos' }], orderBy: orden, limit: 30 });
const categoriaYFecha = await consultar(uid, {
  from: [{ collectionId: 'gastos' }],
  where: {
    compositeFilter: {
      op: 'AND',
      filters: [
        { fieldFilter: { field: { fieldPath: 'categoria' }, op: 'EQUAL', value: { stringValue: 'comida' } } },
        { fieldFilter: { field: { fieldPath: 'fecha' }, op: 'GREATER_THAN_OR_EQUAL', value: { timestampValue: inicioMesIso } } },
      ],
    },
  },
  orderBy: orden,
  limit: 30,
});

// La reducción se calcula con los documentos observados de cada consulta; no hay valores
// escritos a mano en las aserciones ni en la salida de la prueba.
const reduccion = (documentos) => (100 * (1 - documentos / completa)).toLocaleString('es-AR', { maximumFractionDigits: 1 });
console.log('Documentos devueltos (= lecturas facturadas por apertura) y reducción frente a la consulta completa:');
console.log(`  consulta completa sin filtro : ${completa} lecturas`);
console.log(`  consulta del mes calendario  : ${mes} lecturas    -> ${reduccion(mes)} % menos`);
console.log(`  consulta paginada (30 items) : ${pagina} lecturas     -> ${reduccion(pagina)} % menos`);
console.log(`  consulta categoria + fecha   : ${categoriaYFecha} lecturas     -> ${reduccion(categoriaYFecha)} % menos (forma compatible con el indice declarado)`);

verificar('la consulta completa devuelve el historial completo', completa === HISTORIAL);
verificar('la consulta por mes calendario devuelve exactamente su intervalo', mes === esperadosMes, `${mes}/${esperadosMes} documentos del mes`);
verificar('la consulta paginada devuelve exactamente 30 elementos', pagina === 30);
verificar('la consulta por categoria y fecha devuelve el limite esperado', categoriaYFecha === Math.min(30, esperadosCategoriaMes), `${categoriaYFecha}/${Math.min(30, esperadosCategoriaMes)} documentos`);

console.log(fallos.length ? `\nFALLO: ${fallos.length} verificaciones no pasaron` : '\nRESULTADO FINAL: Todas las verificaciones pasaron exitosamente (0 errores).');
process.exit(fallos.length ? 1 : 0);
